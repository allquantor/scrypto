package scorex.crypto.authds.merkle.versioned

import scorex.crypto.authds.merkle.MerkleTree
import scorex.crypto.authds.merkle.MerkleTree.Position
import scorex.crypto.authds.storage._
import scorex.crypto.encode.Base16
import scorex.crypto.hash.CryptographicHash

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.{Failure, Success, Try}


trait VersionedMerkleTree[HashFn <: CryptographicHash, ST <: StorageType]
  extends MerkleTree[HashFn, ST] with VersionedStorage[ST] {

  override protected type Level = VersionedBlobStorage[ST]

  private def even(l: Long) = (l % 2) == 0

  private def applyChanges(levelMap: Level,
                           changes: Seq[(Position, Option[Digest])]) = {
    changes.foreach { case (pos, newDigestOpt) =>
      newDigestOpt match {
        case Some(nd) =>
          levelMap.set(pos, nd)
        case None =>
          levelMap.unset(pos)
      }
    }
  }

  private def pairsToRecalc(level:LevelId,
                            levelMap: Level,
                            changes: Seq[(Position, Option[Digest])]): Map[Position, Option[(Digest, Digest)]] = {
    val pairs = mutable.Map[Position, Option[(Digest, Digest)]]()

    changes.foreach { case (pos, newDigestOpt) =>
      even(pos) match {
        //left
        case true =>
          pairs.put(pos, newDigestOpt.map(newDigest => (newDigest, levelMap.get(pos + 1).getOrElse(emptyTreeHash(level)))))

        //right
        case false =>
          val leftPos = pos - 1

          pairs.get(leftPos) match {
            case Some(pairOpt) =>
              (pairOpt, newDigestOpt) match {
                case (Some(pair), _) =>
                  pairs.put(leftPos, Some(pair._1, newDigestOpt.getOrElse(emptyTreeHash(level))))
                case (None, Some(newDigest)) =>
                  throw new IllegalStateException("This branch must not be reached")
                case (None, None) => //leave None
              }

            case None =>
              //todo: get?
              pairs.put(leftPos, Some(levelMap.get(leftPos).getOrElse(emptyTreeHash(level)), newDigestOpt.getOrElse(emptyTreeHash(0))))
          }
      }
    }
    pairs.toMap
  }

  @tailrec
  final def batchUpdate(changes: Seq[(Position, Option[Digest])],
                        level: Int = 0): VersionedMerkleTree[HashFn, ST] = {

    val levelMap = getLevel(level).get

    applyChanges(levelMap, changes)

    val nextLevelChanges = pairsToRecalc(level, levelMap, changes).map { case (pos, dsOpt) =>
      pos / 2 -> dsOpt.map(ds => hashFunction(ds._1 ++ ds._2))
    }.toSeq

    if (level == height) {
      commit().ensuring(this.consistent)
      this
    } else {
      batchUpdate(nextLevelChanges, level + 1)
    }
  }

  def close(): Unit

  def commit(): Unit

  protected def mapLevels[T](mapFn: Level => T): Try[Seq[T]] =
    (0 to height).foldLeft(Success(Seq()): Try[Seq[T]]) { case (partialResult, i) =>
      partialResult match {
        case Failure(e) =>
          Failure(new Exception(s"Has a problem while mapping a level #$i", e))
        case Success(seq) =>
          Try(getLevel(i).get).map(mapFn).map(e => seq :+ e)
      }
    }

  override def putVersionTag(versionTag: VersionTag): Try[Unit] =
    mapLevels(_.putVersionTag(versionTag)).map(_.find(_.isFailure)) match {
      case Failure(e) => Failure(e)
      case Success(None) => Success(Unit)
      case Success(Some(Failure(e))) => Failure(e)
      case _ => ???
    }

  override def rollbackTo(versionTag: VersionTag): Try[VersionedMerkleTree[HashFn, ST]] =
    mapLevels(_.rollbackTo(versionTag)).flatMap(_.find(_.isFailure) match {
      case Some(Failure(thr)) => Failure(thr)
      case Some(_) => Failure(new Exception("Some(_)"))
      case None => Success(this)
    })

  override def allVersions(): Seq[VersionTag] = getLevel(0).map(_.allVersions()).getOrElse(Seq())

  def consistent: Boolean = mapLevels(_.lastVersion).map(_.toSet.size == 1).getOrElse(false)

  def debugOut(): Unit = (0 to height).foreach { h =>
    val s = getLevel(h).get.size
    val rowString = (0L to (s - 1)).map { pos =>
      s"($pos: ${Base16.encode(getHash(h -> pos).get)})"
    }.mkString
    println(s"$h: $rowString")
  }
}


abstract class MvStoreVersionedMerkleTree[HashFn <: CryptographicHash](val fileNameOpt: Option[String],
                                                                       override val hashFunction: HashFn)
  extends VersionedMerkleTree[HashFn, MvStoreStorageType] {

  protected lazy val levels = mutable.Map[Int, VersionedBlobStorage[MvStoreStorageType]]()

  protected override def createLevel(level: LevelId, versionOpt: Option[VersionTag]): Try[Level] = Try {
    val res = new MvStoreVersionedBlobStorage(fileNameOpt.map(_ + "-" + level + ".mapDB"))
    res.commitAndMark(versionOpt)
    levels += level -> res
    res
  }.recoverWith { case e: Throwable =>
    e.printStackTrace()
    Failure(e)
  }

  protected override def getLevel(level: LevelId): Option[Level] =
    levels
      .get(level)
      .orElse(createLevel(level, levels.get(level - 1).map(_.lastVersion)).toOption)

  override def close(): Unit = {
    commit()
    levels.foreach(_._2.close())
  }

  override def commit(): Unit = levels.foreach(_._2.commitAndMark())
}

object MvStoreVersionedMerkleTree {
  def apply[HashFn <: CryptographicHash](seq: VersionedBlobStorage[_],
                                         fileNameOpt: Option[String],
                                         hashFunction: HashFn): MvStoreVersionedMerkleTree[HashFn] = {
    val tree = new MvStoreVersionedMerkleTree(fileNameOpt, hashFunction) {
      override def size = seq.size
    }.ensuring(_.getLevel(0).get.size == 0)

    tree.batchUpdate((0L to seq.size-1).map(pos => pos -> seq.get(pos).map(hashFunction.apply)))
    tree
  }
}