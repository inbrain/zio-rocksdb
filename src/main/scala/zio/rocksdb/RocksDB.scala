package zio.rocksdb

import org.rocksdb.{ ColumnFamilyDescriptor, ColumnFamilyHandle, ColumnFamilyOptions, RocksIterator }
import org.{ rocksdb => jrocks }
import zio._
import zio.rocksdb.iterator.{ Direction, Position }
import zio.stream.{ Stream, ZStream }

import scala.jdk.CollectionConverters._

object RocksDB extends Operations[RocksDB, service.RocksDB] {
  class Live protected (db: jrocks.RocksDB, cfHandles: List[jrocks.ColumnFamilyHandle]) extends service.RocksDB {

    def createColumnFamily(columnFamilyDescriptor: ColumnFamilyDescriptor): Task[ColumnFamilyHandle] =
      Task(db.createColumnFamily(columnFamilyDescriptor))

    def createColumnFamilies(
      columnFamilyDescriptors: List[ColumnFamilyDescriptor]
    ): Task[List[ColumnFamilyHandle]] =
      Task(db.createColumnFamilies(columnFamilyDescriptors.asJava).asScala.toList)

    def createColumnFamilies(
      columnFamilyOptions: ColumnFamilyOptions,
      columnFamilyNames: List[Array[Byte]]
    ): Task[List[ColumnFamilyHandle]] =
      Task(db.createColumnFamilies(columnFamilyOptions, columnFamilyNames.asJava).asScala.toList)

    def delete(key: Array[Byte]): Task[Unit] =
      Task(db.delete(key))

    def delete(cfHandle: jrocks.ColumnFamilyHandle, key: Array[Byte]): Task[Unit] =
      Task(db.delete(cfHandle, key))

    def dropColumnFamily(columnFamilyHandle: ColumnFamilyHandle): Task[Unit] =
      Task(db.dropColumnFamily(columnFamilyHandle))

    def dropColumnFamilies(columnFamilyHandles: List[ColumnFamilyHandle]): Task[Unit] =
      Task(db.dropColumnFamilies(columnFamilyHandles.asJava))

    def get(key: Array[Byte]): Task[Option[Array[Byte]]] =
      Task(Option(db.get(key)))

    def get(cfHandle: jrocks.ColumnFamilyHandle, key: Array[Byte]): Task[Option[Array[Byte]]] =
      Task(Option(db.get(cfHandle, key)))

    def flush(flushOptions: jrocks.FlushOptions): Task[Unit] = Task(db.flush(flushOptions))

    def flush(flushOptions: jrocks.FlushOptions, columnFamilyHandle: jrocks.ColumnFamilyHandle): Task[Unit] =
      Task(db.flush(flushOptions, columnFamilyHandle))

    def flush(flushOptions: jrocks.FlushOptions, columnFamilyHandles: List[ColumnFamilyHandle]): Task[Unit] =
      Task(db.flush(flushOptions, columnFamilyHandles.asJava))

    def flushWal(sync: Boolean): Task[Unit] = Task(db.flushWal(sync))

    def initialHandles: Task[List[jrocks.ColumnFamilyHandle]] =
      Task.succeed(cfHandles)

    def multiGetAsList(keys: List[Array[Byte]]): Task[List[Option[Array[Byte]]]] =
      Task(db.multiGetAsList(keys.asJava).asScala.toList.map(Option(_)))

    def multiGetAsList(
      handles: List[jrocks.ColumnFamilyHandle],
      keys: List[Array[Byte]]
    ): Task[List[Option[Array[Byte]]]] =
      Task {
        db.multiGetAsList(handles.asJava, keys.asJava).asScala.toList.map(Option(_))
      }

    private def drainIterator(direction: Direction, position: Position)(
      it: jrocks.RocksIterator
    ): Stream[Throwable, (Array[Byte], Array[Byte])] =
      ZStream.fromEffect(Task(set(it, position))).drain ++
        ZStream.fromEffect(Task(it.isValid)).flatMap { valid =>
          if (!valid) ZStream.empty
          else
            ZStream.fromEffect(Task(it.key() -> it.value())) ++ ZStream.repeatEffectOption {
              Task {
                step(it, direction)

                if (!it.isValid) ZIO.fail(None)
                else UIO(it.key() -> it.value())
              }.mapError(Some(_)).flatten
            }
        }

    private def set(it: jrocks.RocksIterator, position: Position): Unit =
      position match {
        case Position.Last        => it.seekToLast()
        case Position.First       => it.seekToFirst()
        case Position.Target(key) => it.seek(key.toArray)
      }

    private def step(it: jrocks.RocksIterator, direction: Direction): Unit =
      direction match {
        case Direction.Forward  => it.next()
        case Direction.Backward => it.prev()
      }

    def newIterator: Stream[Throwable, (Array[Byte], Array[Byte])] =
      newIterator(Direction.Forward, Position.First)

    def newIterator(
      direction: Direction,
      position: Position
    ): Stream[Throwable, (Array[Byte], Array[Byte])] =
      ZStream
        .bracket(Task(db.newIterator()))(it => UIO(it.close()))
        .flatMap(drainIterator(direction, position))

    def newIterator(cfHandle: jrocks.ColumnFamilyHandle): Stream[Throwable, (Array[Byte], Array[Byte])] =
      ZStream
        .bracket(Task(db.newIterator(cfHandle)))(it => UIO(it.close()))
        .flatMap(drainIterator(Direction.Forward, Position.First))

    def newIterators(
      cfHandles: List[jrocks.ColumnFamilyHandle]
    ): Stream[Throwable, (jrocks.ColumnFamilyHandle, Stream[Throwable, (Array[Byte], Array[Byte])])] =
      ZStream
        .bracket(Task(db.newIterators(cfHandles.asJava)))(
          its => UIO.foreach(its.toArray)(it => UIO(it.asInstanceOf[RocksIterator].close()))
        )
        .flatMap { its =>
          ZStream.fromIterable {
            cfHandles.zip(its.asScala.toList.map(drainIterator(Direction.Forward, Position.First)))
          }
        }

    def put(key: Array[Byte], value: Array[Byte]): Task[Unit] =
      Task(db.put(key, value))

    def put(cfHandle: jrocks.ColumnFamilyHandle, key: Array[Byte], value: Array[Byte]): Task[Unit] =
      Task(db.put(cfHandle, key, value))

    def write(writeOptions: jrocks.WriteOptions, writeBatch: WriteBatch): Task[Unit] =
      Task(db.write(writeOptions, writeBatch.getUnderlying))
  }

  object Live {

    def listColumnFamilies(options: jrocks.Options, path: String): Task[List[Array[Byte]]] =
      Task(jrocks.RocksDB.listColumnFamilies(options, path).asScala.toList)

    def open(
      options: jrocks.DBOptions,
      path: String,
      cfDescriptors: List[jrocks.ColumnFamilyDescriptor]
    ): Managed[Throwable, service.RocksDB] = {
      val handles = new java.util.ArrayList[jrocks.ColumnFamilyHandle](cfDescriptors.size)
      val db      = Task(jrocks.RocksDB.open(options, path, cfDescriptors.asJava, handles))

      make(db, handles.asScala.toList)
    }

    def open(path: String): Managed[Throwable, service.RocksDB] =
      make(Task(jrocks.RocksDB.open(path)), Nil)

    def open(options: jrocks.Options, path: String): Managed[Throwable, service.RocksDB] =
      make(Task(jrocks.RocksDB.open(options, path)), Nil)

    private def make(
      db: Task[jrocks.RocksDB],
      cfHandles: List[jrocks.ColumnFamilyHandle]
    ): Managed[Throwable, service.RocksDB] =
      db.toManaged(db => Task(db.closeE()).orDie).map(new Live(_, cfHandles))
  }

  /**
   * Opens the database at the specified path with the specified ColumnFamilies.
   */
  def live(
    options: jrocks.DBOptions,
    path: String,
    cfDescriptors: List[jrocks.ColumnFamilyDescriptor]
  ): ZLayer[Any, Throwable, RocksDB] =
    ZLayer.fromManaged(Live.open(options, path, cfDescriptors))

  /**
   * Opens the default ColumnFamily for the database at the specified path.
   */
  def live(path: String): ZLayer[Any, Throwable, RocksDB] =
    ZLayer.fromManaged(Live.open(path))

  /**
   * Opens the default ColumnFamily for the database at the specified path.
   */
  def live(options: jrocks.Options, path: String): ZLayer[Any, Throwable, RocksDB] =
    ZLayer.fromManaged(Live.open(options, path))
}
