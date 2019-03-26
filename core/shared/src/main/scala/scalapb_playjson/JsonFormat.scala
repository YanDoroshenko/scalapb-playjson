package scalapb_playjson

import com.google.protobuf.ByteString
import com.google.protobuf.descriptor.FieldDescriptorProto
import com.google.protobuf.duration.Duration
import com.google.protobuf.field_mask.FieldMask
import com.google.protobuf.struct.NullValue
import com.google.protobuf.timestamp.Timestamp
import play.api.libs.json._
import scalapb_json._
import scalapb_json.ScalapbJsonCommon.unsignedInt

import scala.collection.mutable
import scala.reflect.ClassTag
import scalapb._
import scalapb.descriptors.{Reads => _, _}
import scalapb_json.ScalapbJsonCommon.GenericCompanion
import scala.util.control.NonFatal

case class Formatter[T](writer: (Printer, T) => JsValue, parser: (Parser, JsValue) => T)

case class FormatRegistry(
  messageFormatters: Map[Class[_], Formatter[_]] = Map.empty,
  enumFormatters: Map[EnumDescriptor, Formatter[EnumValueDescriptor]] = Map.empty,
  registeredCompanions: Seq[GenericCompanion] = Seq.empty
) {

  def registerMessageFormatter[T <: GeneratedMessage](
    writer: (Printer, T) => JsValue,
    parser: (Parser, JsValue) => T
  )(implicit ct: ClassTag[T]): FormatRegistry = {
    copy(messageFormatters = messageFormatters + (ct.runtimeClass -> Formatter(writer, parser)))
  }

  def registerEnumFormatter[E <: GeneratedEnum](
    writer: (Printer, EnumValueDescriptor) => JsValue,
    parser: (Parser, JsValue) => EnumValueDescriptor
  )(implicit cmp: GeneratedEnumCompanion[E]): FormatRegistry = {
    copy(enumFormatters = enumFormatters + (cmp.scalaDescriptor -> Formatter(writer, parser)))
  }

  def registerWriter[T <: GeneratedMessage: ClassTag](
    writer: T => JsValue,
    parser: JsValue => T
  ): FormatRegistry = {
    registerMessageFormatter((p: Printer, t: T) => writer(t), (p: Parser, v: JsValue) => parser(v))
  }

  def getMessageWriter[T](klass: Class[_ <: T]): Option[(Printer, T) => JsValue] = {
    messageFormatters.get(klass).asInstanceOf[Option[Formatter[T]]].map(_.writer)
  }

  def getMessageParser[T](klass: Class[_ <: T]): Option[(Parser, JsValue) => T] = {
    messageFormatters.get(klass).asInstanceOf[Option[Formatter[T]]].map(_.parser)
  }

  def getEnumWriter(
    descriptor: EnumDescriptor
  ): Option[(Printer, EnumValueDescriptor) => JsValue] = {
    enumFormatters.get(descriptor).map(_.writer)
  }

  def getEnumParser(
    descriptor: EnumDescriptor
  ): Option[(Parser, JsValue) => EnumValueDescriptor] = {
    enumFormatters.get(descriptor).map(_.parser)
  }
}

class Printer(
  includingDefaultValueFields: Boolean = false,
  preservingProtoFieldNames: Boolean = false,
  formattingLongAsNumber: Boolean = false,
  formattingEnumsAsNumber: Boolean = false,
  formatRegistry: FormatRegistry = JsonFormat.DefaultRegistry,
  val typeRegistry: TypeRegistry = TypeRegistry.empty
) {
  def print[A](m: GeneratedMessage): String = {
    toJson(m).toString()
  }

  private[this] type JField = (String, JsValue)
  private[this] type FieldBuilder = mutable.Builder[JField, List[JField]]

  private[this] def JField(key: String, value: JsValue) = (key, value)

  private def serializeMessageField(
    fd: FieldDescriptor,
    name: String,
    value: Any,
    b: FieldBuilder
  ): Unit = {
    value match {
      case null =>
      // We are never printing empty optional messages to prevent infinite recursion.
      case Nil =>
        if (includingDefaultValueFields) {
          b += ((name, if (fd.isMapField) Json.obj() else JsArray(Nil)))
        }
      case xs: Iterable[GeneratedMessage] @unchecked =>
        if (fd.isMapField) {
          val mapEntryDescriptor = fd.scalaType.asInstanceOf[ScalaType.Message].descriptor
          val keyDescriptor = mapEntryDescriptor.findFieldByNumber(1).get
          val valueDescriptor = mapEntryDescriptor.findFieldByNumber(2).get
          b += JField(
            name,
            JsObject(xs.iterator.map { x =>
              val key = x.getField(keyDescriptor) match {
                case PBoolean(v) => v.toString
                case PDouble(v) => v.toString
                case PFloat(v) => v.toString
                case PInt(v) => v.toString
                case PLong(v) => v.toString
                case PString(v) => v
                case v => throw new JsonFormatException(s"Unexpected value for key: $v")
              }
              val value = if (valueDescriptor.protoType.isTypeMessage) {
                toJson(x.getFieldByNumber(valueDescriptor.number).asInstanceOf[GeneratedMessage])
              } else {
                serializeSingleValue(
                  valueDescriptor,
                  x.getField(valueDescriptor),
                  formattingLongAsNumber
                )
              }
              key -> value
            }.toList)
          )
        } else {
          b += JField(name, JsArray(xs.iterator.map(toJson).toList))
        }
      case msg: GeneratedMessage =>
        b += JField(name, toJson(msg))
      case v =>
        throw new JsonFormatException(v.toString)
    }
  }

  private def serializeNonMessageField(
    fd: FieldDescriptor,
    name: String,
    value: PValue,
    b: FieldBuilder
  ) = {
    value match {
      case PEmpty =>
        if (includingDefaultValueFields && fd.containingOneof.isEmpty) {
          b += JField(name, defaultJsValue(fd))
        }
      case PRepeated(xs) =>
        if (xs.nonEmpty || includingDefaultValueFields) {
          b += JField(name, JsArray(xs.map(serializeSingleValue(fd, _, formattingLongAsNumber))))
        }
      case v =>
        if (includingDefaultValueFields ||
          !fd.isOptional ||
          !fd.file.isProto3 ||
          (v != scalapb_json.ScalapbJsonCommon.defaultValue(fd)) ||
          fd.containingOneof.isDefined) {
          b += JField(name, serializeSingleValue(fd, v, formattingLongAsNumber))
        }
    }
  }

  def toJson[A <: GeneratedMessage](m: A): JsValue = {
    formatRegistry.getMessageWriter[A](m.getClass) match {
      case Some(f) => f(this, m)
      case None =>
        val b = List.newBuilder[JField]
        val descriptor = m.companion.scalaDescriptor
        b.sizeHint(descriptor.fields.size)
        descriptor.fields.foreach { f =>
          val name =
            if (preservingProtoFieldNames) f.name else scalapb_json.ScalapbJsonCommon.jsonName(f)
          if (f.protoType.isTypeMessage) {
            serializeMessageField(f, name, m.getFieldByNumber(f.number), b)
          } else {
            serializeNonMessageField(f, name, m.getField(f), b)
          }
        }
        JsObject(b.result())
    }
  }

  private def defaultJsValue(fd: FieldDescriptor): JsValue =
    serializeSingleValue(
      fd,
      scalapb_json.ScalapbJsonCommon.defaultValue(fd),
      formattingLongAsNumber
    )

  private def unsignedLong(n: Long) =
    if (n < 0) BigDecimal(BigInt(n & 0X7FFFFFFFFFFFFFFFL).setBit(63)) else BigDecimal(n)

  private def formatLong(
    n: Long,
    protoType: FieldDescriptorProto.Type,
    formattingLongAsNumber: Boolean
  ): JsValue = {
    val v =
      if (protoType.isTypeUint64 || protoType.isTypeFixed64) unsignedLong(n) else BigDecimal(n)
    if (formattingLongAsNumber) JsNumber(v) else JsString(v.toString())
  }

  def serializeSingleValue(
    fd: FieldDescriptor,
    value: PValue,
    formattingLongAsNumber: Boolean
  ): JsValue = value match {
    case PEnum(e) =>
      formatRegistry.getEnumWriter(e.containingEnum) match {
        case Some(writer) => writer(this, e)
        case None => if (formattingEnumsAsNumber) JsNumber(e.number) else JsString(e.name)
      }
    case PInt(v) if fd.protoType.isTypeUint32 => JsNumber(unsignedInt(v))
    case PInt(v) if fd.protoType.isTypeFixed32 => JsNumber(unsignedInt(v))
    case PInt(v) => JsNumber(v)
    case PLong(v) => formatLong(v, fd.protoType, formattingLongAsNumber)
    case PDouble(v) => Printer.doubleToJson(v)
    case PFloat(v) => Printer.floatToJson(v)
    case PBoolean(v) => JsBoolean(v)
    case PString(v) => JsString(v)
    case PByteString(v) => JsString(java.util.Base64.getEncoder.encodeToString(v.toByteArray))
    case _: PMessage | PRepeated(_) | PEmpty => throw new RuntimeException("Should not happen")
  }
}

object Printer {
  private[this] val JsStringPosInfinity = JsString("Infinity")
  private[this] val JsStringNegInfinity = JsString("-Infinity")
  private[this] val JsStringNaN = JsString("NaN")

  private[scalapb_playjson] def doubleToJson(value: Double): JsValue = {
    if (value.isPosInfinity) {
      JsStringPosInfinity
    } else if (value.isNegInfinity) {
      JsStringNegInfinity
    } else if (java.lang.Double.isNaN(value)) {
      JsStringNaN
    } else {
      JsNumber(value)
    }
  }

  private def floatToJson(value: Float): JsValue = {
    if (value.isPosInfinity) {
      JsStringPosInfinity
    } else if (value.isNegInfinity) {
      JsStringNegInfinity
    } else if (java.lang.Double.isNaN(value)) {
      JsStringNaN
    } else {
      JsNumber(value)
    }
  }
}

class Parser(
  preservingProtoFieldNames: Boolean = false,
  formatRegistry: FormatRegistry = JsonFormat.DefaultRegistry,
  val typeRegistry: TypeRegistry = TypeRegistry.empty
) {

  def fromJsonString[A <: GeneratedMessage with Message[A]](
    str: String
  )(implicit cmp: GeneratedMessageCompanion[A]): A = {
    fromJson(Json.parse(str))
  }

  def fromJson[A <: GeneratedMessage with Message[A]](
    value: JsValue
  )(implicit cmp: GeneratedMessageCompanion[A]): A = {
    cmp.messageReads.read(fromJsonToPMessage(cmp, value))
  }

  private def serializedName(fd: FieldDescriptor): String = {
    if (preservingProtoFieldNames) fd.asProto.getName
    else scalapb_json.ScalapbJsonCommon.jsonName(fd)
  }

  private def fromJsonToPMessage(cmp: GeneratedMessageCompanion[_], value: JsValue): PMessage = {

    def parseValue(fd: FieldDescriptor, value: JsValue): PValue = {
      if (fd.isMapField) {
        value match {
          case JsObject(vals) =>
            val mapEntryDesc = fd.scalaType.asInstanceOf[ScalaType.Message].descriptor
            val keyDescriptor = mapEntryDesc.findFieldByNumber(1).get
            val valueDescriptor = mapEntryDesc.findFieldByNumber(2).get
            PRepeated(vals.iterator.map {
              case (key, jValue) =>
                val keyObj = keyDescriptor.scalaType match {
                  case ScalaType.Boolean => PBoolean(java.lang.Boolean.valueOf(key))
                  case ScalaType.Double => PDouble(java.lang.Double.valueOf(key))
                  case ScalaType.Float => PFloat(java.lang.Float.valueOf(key))
                  case ScalaType.Int => PInt(java.lang.Integer.valueOf(key))
                  case ScalaType.Long => PLong(java.lang.Long.valueOf(key))
                  case ScalaType.String => PString(key)
                  case _ => throw new RuntimeException(s"Unsupported type for key for ${fd.name}")
                }
                PMessage(
                  Map(
                    keyDescriptor -> keyObj,
                    valueDescriptor -> parseSingleValue(
                      cmp.messageCompanionForFieldNumber(fd.number),
                      valueDescriptor,
                      jValue
                    )
                  )
                )
            }.toVector)
          case _ =>
            throw new JsonFormatException(
              s"Expected an object for map field ${serializedName(fd)} of ${fd.containingMessage.name}"
            )
        }
      } else if (fd.isRepeated) {
        value match {
          case JsArray(vals) =>
            PRepeated(vals.iterator.map(parseSingleValue(cmp, fd, _)).toVector)
          case _ =>
            throw new JsonFormatException(
              s"Expected an array for repeated field ${serializedName(fd)} of ${fd.containingMessage.name}"
            )
        }
      } else parseSingleValue(cmp, fd, value)
    }

    formatRegistry.getMessageParser(cmp.defaultInstance.getClass) match {
      case Some(p) => p(this, value).asInstanceOf[GeneratedMessage].toPMessage
      case None =>
        value match {
          case JsObject(fields) =>
            val values: Map[String, JsValue] = fields.iterator.map(k => k._1 -> k._2).toMap

            val valueMap: Map[FieldDescriptor, PValue] = (for {
              fd <- cmp.scalaDescriptor.fields
              jsValue <- values.get(serializedName(fd)) if jsValue != JsNull
            } yield (fd, parseValue(fd, jsValue))).toMap

            PMessage(valueMap)
          case _ =>
            throw new JsonFormatException(s"Expected an object, found ${value}")
        }
    }
  }

  def defaultEnumParser(enumDescriptor: EnumDescriptor, value: JsValue): EnumValueDescriptor =
    value match {
      case JsNumber(v) =>
        enumDescriptor
          .findValueByNumber(v.toInt)
          .getOrElse(
            throw new JsonFormatException(
              s"Invalid enum value: ${v.toInt} for enum type: ${enumDescriptor.fullName}"
            )
          )
      case JsString(s) =>
        enumDescriptor.values
          .find(_.name == s)
          .getOrElse(throw new JsonFormatException(s"Unrecognized enum value '${s}'"))
      case _ =>
        throw new JsonFormatException(
          s"Unexpected value ($value) for enum ${enumDescriptor.fullName}"
        )
    }

  protected def parseSingleValue(
    containerCompanion: GeneratedMessageCompanion[_],
    fd: FieldDescriptor,
    value: JsValue
  ): PValue = fd.scalaType match {
    case ScalaType.Enum(ed) =>
      PEnum(formatRegistry.getEnumParser(ed) match {
        case Some(parser) => parser(this, value)
        case None => defaultEnumParser(ed, value)
      })
    case ScalaType.Message(_) =>
      fromJsonToPMessage(containerCompanion.messageCompanionForFieldNumber(fd.number), value)
    case st =>
      JsonFormat.parsePrimitive(
        st,
        fd.protoType,
        value,
        throw new JsonFormatException(
          s"Unexpected value ($value) for field ${serializedName(fd)} of ${fd.containingMessage.name}"
        )
      )
  }
}

object JsonFormat {
  import com.google.protobuf.wrappers
  import scalapb_json.ScalapbJsonCommon._

  val DefaultRegistry = FormatRegistry()
    .registerWriter(
      (d: Duration) => JsString(Durations.writeDuration(d)), {
        case JsString(str) => Durations.parseDuration(str)
        case _ => throw new JsonFormatException("Expected a string.")
      }
    )
    .registerWriter(
      (t: Timestamp) => JsString(Timestamps.writeTimestamp(t)), {
        case JsString(str) => Timestamps.parseTimestamp(str)
        case _ => throw new JsonFormatException("Expected a string.")
      }
    )
    .registerWriter(
      (f: FieldMask) => JsString(ScalapbJsonCommon.fieldMaskToJsonString(f)), {
        case JsString(str) =>
          ScalapbJsonCommon.fieldMaskFromJsonString(str)
        case _ =>
          throw new JsonFormatException("Expected a string.")
      }
    )
    .registerMessageFormatter[wrappers.DoubleValue](
      primitiveWrapperWriter,
      primitiveWrapperParser[wrappers.DoubleValue]
    )
    .registerMessageFormatter[wrappers.FloatValue](
      primitiveWrapperWriter,
      primitiveWrapperParser[wrappers.FloatValue]
    )
    .registerMessageFormatter[wrappers.Int32Value](
      primitiveWrapperWriter,
      primitiveWrapperParser[wrappers.Int32Value]
    )
    .registerMessageFormatter[wrappers.Int64Value](
      primitiveWrapperWriter,
      primitiveWrapperParser[wrappers.Int64Value]
    )
    .registerMessageFormatter[wrappers.UInt32Value](
      primitiveWrapperWriter,
      primitiveWrapperParser[wrappers.UInt32Value]
    )
    .registerMessageFormatter[wrappers.UInt64Value](
      primitiveWrapperWriter,
      primitiveWrapperParser[wrappers.UInt64Value]
    )
    .registerMessageFormatter[wrappers.BoolValue](
      primitiveWrapperWriter,
      primitiveWrapperParser[wrappers.BoolValue]
    )
    .registerMessageFormatter[wrappers.BytesValue](
      primitiveWrapperWriter,
      primitiveWrapperParser[wrappers.BytesValue]
    )
    .registerMessageFormatter[wrappers.StringValue](
      primitiveWrapperWriter,
      primitiveWrapperParser[wrappers.StringValue]
    )
    .registerEnumFormatter[NullValue](
      (_, _) => JsNull,
      (parser, value) =>
        value match {
          case JsNull => NullValue.NULL_VALUE.scalaValueDescriptor
          case _ => parser.defaultEnumParser(NullValue.scalaDescriptor, value)
        }
    )
    .registerWriter[com.google.protobuf.struct.Value](
      StructFormat.structValueWriter,
      StructFormat.structValueParser
    )
    .registerWriter[com.google.protobuf.struct.Struct](
      StructFormat.structWriter,
      StructFormat.structParser
    )
    .registerWriter[com.google.protobuf.struct.ListValue](
      StructFormat.listValueWriter,
      StructFormat.listValueParser
    )
    .registerMessageFormatter[com.google.protobuf.any.Any](AnyFormat.anyWriter, AnyFormat.anyParser)

  def primitiveWrapperWriter[T <: GeneratedMessage with Message[T]](
    implicit cmp: GeneratedMessageCompanion[T]
  ): ((Printer, T) => JsValue) = {
    val fieldDesc = cmp.scalaDescriptor.findFieldByNumber(1).get
    (printer, t) =>
      printer.serializeSingleValue(fieldDesc, t.getField(fieldDesc), formattingLongAsNumber = false)
  }

  def primitiveWrapperParser[T <: GeneratedMessage with Message[T]](
    implicit cmp: GeneratedMessageCompanion[T]
  ): ((Parser, JsValue) => T) = {
    val fieldDesc = cmp.scalaDescriptor.findFieldByNumber(1).get
    (parser, jv) =>
      cmp.messageReads.read(
        PMessage(
          Map(
            fieldDesc -> JsonFormat.parsePrimitive(
              fieldDesc.scalaType,
              fieldDesc.protoType,
              jv,
              throw new JsonFormatException(s"Unexpected value for ${cmp.scalaDescriptor.name}")
            )
          )
        )
      )
  }

  val printer = new Printer()
  val parser = new Parser()

  def toJsonString[A <: GeneratedMessage](m: A): String = printer.print(m)

  def toJson[A <: GeneratedMessage](m: A): JsValue = printer.toJson(m)

  def fromJson[A <: GeneratedMessage with Message[A]: GeneratedMessageCompanion](
    value: JsValue
  ): A = {
    parser.fromJson(value)
  }

  def fromJsonString[A <: GeneratedMessage with Message[A]: GeneratedMessageCompanion](
    str: String
  ): A = {
    parser.fromJsonString(str)
  }

  implicit def protoToReader[T <: GeneratedMessage with Message[T]: GeneratedMessageCompanion]
    : Reads[T] =
    new Reads[T] {
      def reads(value: JsValue) =
        try {
          JsSuccess(parser.fromJson(value))
        } catch {
          case NonFatal(e) =>
            JsError(JsonValidationError(e.toString, e))
        }
    }

  implicit def protoToWriter[T <: GeneratedMessage with Message[T]]: Writes[T] = new Writes[T] {
    def writes(obj: T): JsValue = printer.toJson(obj)
  }

  def parsePrimitive(
    scalaType: ScalaType,
    protoType: FieldDescriptorProto.Type,
    value: JsValue,
    onError: => PValue
  ): PValue = (scalaType, value) match {
    case (ScalaType.Int, JsNumber(x)) => PInt(x.intValue)
    case (ScalaType.Int, JsString(x)) if protoType.isTypeInt32 => parseInt32(x)
    case (ScalaType.Int, JsString(x)) if protoType.isTypeSint32 => parseInt32(x)
    case (ScalaType.Int, JsString(x)) => parseUint32(x)
    case (ScalaType.Long, JsString(x)) if protoType.isTypeInt64 => parseInt64(x)
    case (ScalaType.Long, JsString(x)) if protoType.isTypeSint64 => parseInt64(x)
    case (ScalaType.Long, JsString(x)) => parseUint64(x)
    case (ScalaType.Long, JsNumber(x)) => PLong(x.toLong)
    case (ScalaType.Double, JsNumber(x)) => PDouble(x.toDouble)
    case (ScalaType.Double, JsString("NaN")) => PDouble(Double.NaN)
    case (ScalaType.Double, JsString("Infinity")) => PDouble(Double.PositiveInfinity)
    case (ScalaType.Double, JsString("-Infinity")) => PDouble(Double.NegativeInfinity)
    case (ScalaType.Float, JsNumber(x)) => PFloat(x.toFloat)
    case (ScalaType.Float, JsString("NaN")) => PFloat(Float.NaN)
    case (ScalaType.Float, JsString("Infinity")) => PFloat(Float.PositiveInfinity)
    case (ScalaType.Float, JsString("-Infinity")) => PFloat(Float.NegativeInfinity)
    case (ScalaType.Boolean, JsBoolean(b)) => PBoolean(b)
    case (ScalaType.String, JsString(s)) => PString(s)
    case (ScalaType.ByteString, JsString(s)) =>
      PByteString(ByteString.copyFrom(java.util.Base64.getDecoder.decode(s)))
    case _ => onError
  }

}
