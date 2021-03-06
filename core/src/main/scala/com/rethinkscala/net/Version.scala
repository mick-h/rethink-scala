package com.rethinkscala.net

import java.io.IOException
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.concurrent.{Executors, TimeUnit}

import com.rethinkscala.ast._
import com.rethinkscala.reflect.Reflector
import com.rethinkscala.{ResultExtractor, Term, TermAssocPair}
import com.typesafe.scalalogging.slf4j.LazyLogging
import ql2.Ql2.{Query, VersionDummy}
import io.netty.buffer.{Unpooled, ByteBuf}
import ql2.{Ql2 => ql2}
import io.netty.channel.Channel

import scala.beans.BeanProperty
import scala.concurrent.{ExecutionContext, Promise}


/**
 * Created with IntelliJ IDEA.
 * User: keyston
 * Date: 7/3/13
 * Time: 5:22 PM
 *
 */

trait CompiledAst

case class ProtoBufCompiledAst(underlying: ql2.Term) extends CompiledAst


trait JsonAst {

  def toValue: Any
}

case class NoneJsonAst() extends JsonAst {
  override def toValue = None
}


case class DatumJsonAst(datumType: ql2.Datum.DatumType, value: Any) extends JsonAst {

  override def toValue = value
}


case class TermJsonAst(termType: ql2.Term.TermType, term: Seq[JsonAst], opts: Map[String, Any] = Map.empty)

  extends JsonAst {

  def options = opts.mapValues {
    case v: JsonAst => v.toValue
    case a: Any => a
  }

  def toValue = termType match {
    case ql2.Term.TermType.MAKE_OBJ => options

    case _ => if
    (opts.nonEmpty) Seq(termType.getNumber, term.map(_.toValue), options)
    else Seq(termType.getNumber, term.map(_.toValue))
  }
}


case class JsonCompiledAst(underlying: JsonAst) extends CompiledAst


trait CompiledQuery {

  type Result
  val tokenId: Long

  protected final def newBuffer(size: Int) = Unpooled.buffer(size).order(ByteOrder.LITTLE_ENDIAN)

  def encode(out: ByteBuf): Unit

  type TokenType[R]

  def asToken(conn: Connection, connectionId: Long, term: Term, promise: Promise[Result])(implicit extractor: ResultExtractor[Result]): TokenType[Result]

  def cursor(token: Token[_], connectionId: Long, completed: Boolean)(factory: CursorFactory) = {
    factory.apply[Result](token, connectionId, completed)
  }
}

class ProtoBufCompiledQuery[T](underlying: Query) extends CompiledQuery {

  override type Result = T

  override def asToken(conn: Connection, connectionId: Long, term: Term, promise: Promise[Result])(implicit extractor: ResultExtractor[Result]) =
    QueryToken[Result](conn, connectionId, this, term, promise)

  override val tokenId: Long = underlying.getToken

  override def encode(out: ByteBuf) = {
    val size = underlying.getSerializedSize
    out.capacity(size + 4)
      .writeInt(size)
      .writeBytes(underlying.toByteArray)

  }

  override type TokenType[R] = QueryToken[R]

}

case class JsonQuery(queryType: ql2.Query.QueryType, ast: JsonAst, opts: Map[String, Any]) {
  def toSeq = {
    var seq: Seq[Any] = Seq(queryType.getNumber)
    if (!ast.isInstanceOf[NoneJsonAst]) seq = seq.:+(ast.toValue)
    if (opts.nonEmpty) seq = seq.:+(opts)
    seq
  }


}

case class JsonCompiledQuery[T](tokenId: Long, query: JsonQuery) extends CompiledQuery {

  override type Result = T

  override def asToken(conn: Connection, connectionId: Long, term: Term, promise: Promise[Result])(implicit extractor: ResultExtractor[Result]) = JsonQueryToken[Result](conn, connectionId, this, term, promise)

  lazy val json = Reflector.toJson(query.toSeq)

  private val TOKEN_SIZE = 8

  override def encode(out: ByteBuf) = {
    val jsonBytes = json.getBytes
    val jsonSize = jsonBytes.length
    val size = TOKEN_SIZE + 4 + jsonSize // token + json len + json string
    out.capacity(size)
      .writeLong(tokenId)
      .writeInt(jsonSize)
      .writeBytes(jsonBytes)

  }


  override type TokenType[R] = JsonQueryToken[R]


}

abstract class Version extends LazyLogging {

  val host: String
  val port: Int
  val maxConnections: Int
  val db: Option[String]
  val timeout: Int = 10

  val version: VersionDummy.Version = VersionDummy.Version.V0_2


  private[rethinkscala] val channelInitializer: RethinkChannelInitializer


  val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5))

  type ResponseType

  def newHandler: VersionHandler[ResponseType]

  private[rethinkscala] var connectTimeout: Option[Long] = None

  def toAst(term: Term): CompiledAst

  type Query[T] <: CompiledQuery {type Result = T}

  type Ast

  def toQuery[T](term: Term, token: Long, db: Option[String] = None, opts: Map[String, Any] = Map()): Query[T]
}


trait ProvidesQuery {
  self: Version =>
  type Builder

  type Ast = CompiledAst

  def newQueryBuilder(queryType: ql2.Query.QueryType, token: Long, opts: Map[String, Any]): Builder

  def withDB(builder: Builder, db: DB): Builder

  def toAst(term: Term): Ast

  def compile[T](builder: Builder, term: Term): Query[T]


  def toQuery[T](term: Term, token: Long, db: Option[String] = None, opts: Map[String, Any] = Map.empty) = {


    val builder = term match {
      case internal.Continue(tokenId) => newQueryBuilder(Query.QueryType.CONTINUE, token, opts)
      case _ =>
        val builder = newQueryBuilder(Query.QueryType.START, token, opts)


        opts.get("db").map {
          case name: String => withDB(builder, DB(name))
        }.getOrElse {
          term match {
            case d: WithDB => d.db.map(withDB(builder, _)).getOrElse(db.map {
              name => withDB(builder, DB(name))
            }.getOrElse(builder))
            case _ => db.map {
              name => withDB(builder, DB(name))
            }.getOrElse(builder)
          }

        }

    }



    compile[T](builder, term)
  }
}

trait ProvidesProtoBufQuery extends ProvidesQuery {

  self: Version =>

  import scala.collection.JavaConversions.{asJavaCollection, seqAsJavaList}


  def toAst(term: Term) = ProtoBufCompiledAst(ast(term))

  def build(ta: TermAssocPair) = ql2.Term.AssocPair.newBuilder.setKey(ta.key).setVal(ast(ta.token)).build()

  type Builder = Query.Builder

  private[this] def ast(term: Term): ql2.Term = {

    val opts = term.optargs.map {
      case ta: TermAssocPair => build(ta)
      // case da:DatumAssocPair=> build(da)
    }

    val builder = term match {
      case d: Datum => {
        val db = ql2.Datum.newBuilder.setType(d.datumType)
        d match {
          case s: StringDatum => db.setRStr(s.value)
          case b: BooleanDatum => db.setRBool(b.value)
          case n: NoneDatum =>
          case n: NumberDatum => db.setRNum(n.value)
        }
        ql2.Term.newBuilder().setDatum(db.build())


      }
      case _ => ql2.Term.newBuilder()
    }



    builder.setType(term.termType)
      .addAllArgs(term.args.map(ast))
      .addAllOptargs(opts).build()
  }


  def newQueryBuilder(queryType: ql2.Query.QueryType, token: Long, opts: Map[String, Any]): Builder = {
    val q = Query.newBuilder().setType(Query.QueryType.START).setToken(token).setAcceptsRJson(true)
    opts.foreach {
      case (key, value) => q.addGlobalOptargs(ql2.Query.AssocPair.newBuilder.setKey(key).setVal(ast(Expr(value))))
    }
    q

  }

  override type Query[T] = ProtoBufCompiledQuery[T]

  def withDB(q: Builder, db: DB) = q.addGlobalOptargs(Query.AssocPair.newBuilder.setKey("db").setVal(ast(db)))

  def compile[T](builder: Builder, term: Term) = new ProtoBufCompiledQuery[T](builder.setQuery(ast(term)).build())
}


abstract class Builder {

  @BeanProperty
  var host: String = "localhost"
  @BeanProperty
  var port: Int = 2801
  @BeanProperty
  var maxConnections: Int = 5
  @BeanProperty
  var db: String = ""
  @BeanProperty
  var timeout: Int = 10

  def build: Version

}


@deprecated("Use Version3")
case class Version2(host: String = "localhost", port: Int = 28015,
                    db: Option[String] = None, maxConnections: Int = 5,
                    authKey: String = "") extends Version with ProvidesProtoBufQuery {
  override private[rethinkscala] val channelInitializer = ProtoChannelInitializer
  override type ResponseType = ql2.Response

  override def newHandler = new ProtoVersionHandler(this)

  override def toString = "Version3"
}


trait ProvidesJsonQuery extends ProvidesQuery {

  self: Version =>

  override type Query[T] = JsonCompiledQuery[T]

  case class QueryBuilder(queryType: ql2.Query.QueryType, token: Long, opts: Map[String, Any] = Map.empty)

  override type Builder = QueryBuilder

  override def withDB(builder: Builder, db: DB) = builder.copy(opts = (builder.opts /: Map("db" -> db.name))(_ + _))

  override def newQueryBuilder(queryType: ql2.Query.QueryType, token: Long, opts: Map[String, Any]) =
    QueryBuilder(queryType, token, opts)

  override def compile[T](builder: Builder, term: Term) = new JsonCompiledQuery[T](builder.token, JsonQuery(builder.queryType, ast(term), builder.opts))


  override def toAst(term: Term) = JsonCompiledAst(ast(term))


  private[this] def ast(term: Term): JsonAst = {

    val opts = term.optargs.map {
      case ta: TermAssocPair => ta.key -> ast(ta.token)
    }.toMap


    term match {
      case internal.Continue(_) => NoneJsonAst()
      case d: Datum => DatumJsonAst(d.datumType, d.value)
      case _ => TermJsonAst(term.termType, term match {
        case i: Insert[_, _] => i.argsForJson.map(ast)
        case _ => term.args.map(ast)
      }, opts)
    }

  }

}


case class Version3(host: String = "localhost", port: Int = 28015,
                    db: Option[String] = None, maxConnections: Int = 5,
                    authKey: String = "") extends Version with ProvidesJsonQuery {

  override type ResponseType = String

  override def newHandler = new JsonVersionHandler(this)

  override private[rethinkscala] val channelInitializer = JsonChannelInitializer(this)
  override val version = VersionDummy.Version.V0_3

  def withConnectTimeout(timeout: Long) = {
    connectTimeout = Some(timeout)
    this
  }

}

trait Versions {

  def Version2(host: String = "localhost", port: Int = 28015,
               db: Option[String] = None, maxConnections: Int = 5,
               authKey: String = "") = new Version2(host, port, db, maxConnections, authKey)

  
  def Version3(host: String = "localhost", port: Int = 28015,
               db: Option[String] = None, maxConnections: Int = 5,
               authKey: String = "") = new Version3(host, port, db, maxConnections, authKey)  

}