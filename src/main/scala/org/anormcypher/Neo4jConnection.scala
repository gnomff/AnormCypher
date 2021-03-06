package org.anormcypher

import play.api.libs.iteratee.{Enumerator, Iteratee}
import scala.concurrent.{Future, ExecutionContext}
import scala.util.control.ControlThrowable

/** Neo4j Connection API */
trait Neo4jConnection {
  @deprecated("0.9", "use execute instead")
  def sendQuery(cypherStatement: CypherStatement)(implicit ec: ExecutionContext): Future[Seq[CypherResultRow]] =
    execute(cypherStatement)

  /** Asynchronous, non-streaming query */
  def execute(stmt: CypherStatement)(implicit ec: ExecutionContext): Future[Seq[CypherResultRow]] =
      streamAutocommit(stmt) |>>> Iteratee.getChunks[CypherResultRow]

  /**
   * Asynchronous, streaming (i.e. reactive) query.
   *
   * Because this method is used to deal with large datasets, it is
   * always executed within its own transaction, which is then
   * immediately commited, regardless of the value for `autocommit`.
   * It will also never participate in any existing transaction.
   */
  def streamAutocommit(stmt: CypherStatement)(implicit ec: ExecutionContext): Enumerator[CypherResultRow]

  private[anormcypher] def beginTx(implicit ec: ExecutionContext): Future[Neo4jTransaction]

  /**
   * Executes the cypher statement in the current open transaction.
   *
   * This method is non-streaming because statements that need to
   * execute in a transaction usually do not return large result sets
   * as it is impractical to hold open the transaction for too long.
   */
  private[anormcypher] def executeInTx(stmt: CypherStatement)(
    implicit tx: Neo4jTransaction, ec: ExecutionContext): Future[Seq[CypherResultRow]]
}

trait Neo4jTransaction {
  def cypher(stmt: CypherStatement)(implicit ec: ExecutionContext): Future[Seq[CypherResultRow]]

  def cypherStream(stmt: CypherStatement)(implicit ec: ExecutionContext): Enumerator[CypherResultRow]

  def txId: String
  // Both commit and rollback are blocking operations because a callback api is not as clear
  def commit(implicit ec: ExecutionContext): Unit
  def rollback(implicit ec: ExecutionContext): Unit

  @inline protected def nosup(msg: String) = throw new UnsupportedOperationException(msg)
}

/** Provides a default single-request, autocommit Transaction  */
object Neo4jTransaction {
  /**
   * Uses the Neo4jConnection in the implicit scope.
   *
   * Client code can shadow this implicit instance by providing its
   * own Neo4jTransaction implementation in the local scope.
   */
  implicit def autocommitNeo4jTransaction(implicit conn: Neo4jConnection): Neo4jTransaction =
    new Neo4jTransaction {
      override def cypher(stmt: CypherStatement)(implicit ec: ExecutionContext) =
        conn.execute(stmt)
      override def cypherStream(stmt: CypherStatement)(implicit ec: ExecutionContext) =
        conn.streamAutocommit(stmt)

      // return a string instead of throwing as it's a legitimate use
      // case for client to query the transaction id for logging
      override val txId = "No transaction id available in autocommit transaction"
      override def commit(implicit ec: ExecutionContext) = nosup("Cannot commit an autocommit transaction")
      override def rollback(implicit ec: ExecutionContext) = nosup("Cannot rollback an autocommit transaction")
    }

  /** Loan Pattern encapsulates transaction lifecycle */
  def withTx[A](code: Neo4jTransaction => A)(implicit conn: Neo4jConnection, ec: ExecutionContext): Future[A] =
    for {
      tx <- conn.beginTx
    } yield try {
      val r = code(tx)
      tx.commit
      r
    } catch {
      case e: ControlThrowable => tx.commit; throw e
      case e: Throwable =>      tx.rollback; throw e
    }
}

/** Provides Transaction that rollsback instead of commiting  */
object Neo4jRollbackTransaction {
  /**
    * Uses the Neo4jConnection in the implicit scope.
    *
    * Client code can shadow this implicit instance by providing its
    * own Neo4jTransaction implementation in the local scope.
    */
  implicit def autocommitNeo4jTransaction(implicit conn: Neo4jConnection): Neo4jTransaction =
  new Neo4jTransaction {
    override def cypher(stmt: CypherStatement)(implicit ec: ExecutionContext) =
      conn.execute(stmt)
    override def cypherStream(stmt: CypherStatement)(implicit ec: ExecutionContext) =
      throw new NotImplementedError("You can not run an autocommit tx from a rollback tx")

    // return a string instead of throwing as it's a legitimate use
    // case for client to query the transaction id for logging
    override val txId = "No transaction id available in rollback transaction"
    override def commit(implicit ec: ExecutionContext) = nosup("Cannot commit a rollback transaction")
    override def rollback(implicit ec: ExecutionContext) = nosup("Rollbacks happen automatically in a rollback transaction")
  }

  /** Loan Pattern encapsulates transaction lifecycle */
  def withTx[A](code: Neo4jTransaction => A)(implicit conn: Neo4jConnection, ec: ExecutionContext): Future[A] =
  for {
    tx <- conn.beginTx
  } yield try {
    val r = code(tx)
    tx.rollback
    r
  } catch {
    case e: ControlThrowable => tx.rollback; throw e
    case e: Throwable =>      tx.rollback; throw e
  }
}