package com.daumkakao.s2graph.core
import GraphUtil._
import KGraphExceptions._
import org.apache.hadoop.hbase.util.Bytes
import scala.collection.mutable.ListBuffer
import org.apache.hadoop.hbase.Cell
import scala.collection.mutable.ArrayBuffer


object HBaseElement {
  val ttsForActivity = 60 * 60 * 24 * 30
  val delimiter = "|"
  val seperator = ":"
  val bytesForMurMur = 2
  val bitsForDir = 2
  val bytesForOp = 3
  val bitsForLenWithDir = 5
  val bitsForDirWithLen = 2
  val bitsForOp = 3

  val bitForPropMode = 1
  val bitForByte = 7
  /**
   * id between application and Graph Instance.
   */
  object CompositeId {
    val defaultColId = 0
    val defaultInnerId = 0
    val isDescOrder = false
    val emptyCompositeId = CompositeId(defaultColId, InnerVal.withLong(defaultInnerId), isEdge = true, useHash = true)
    def apply(bytes: Array[Byte], offset: Int, isEdge: Boolean, useHash: Boolean): CompositeId = {
      var pos = offset
      if (useHash) {
        // skip over murmur hash
        pos += 2
      }
      val innerId = InnerVal(bytes, pos)
      pos += innerId.bytes.length
      if (isEdge) {
        CompositeId(defaultColId, innerId, true, useHash)
      } else {
        val cId = Bytes.toInt(bytes, pos, 4)
        CompositeId(cId, innerId, false, useHash)
      }
    }
  }
  // TODO: colId range < (1<<15??) id length??
  case class CompositeId(colId: Int, innerId: InnerVal, isEdge: Boolean, useHash: Boolean) {
    //    play.api.Logger.debug(s"$this")
    lazy val hash = murmur3(innerId.value.toString)
    lazy val bytes = {
      var ret = if (useHash) Bytes.toBytes(hash) else Array.empty[Byte]
      isEdge match {
        case false =>
          Bytes.add(ret, innerId.bytes, Bytes.toBytes(colId))
        case true => Bytes.add(ret, innerId.bytes)
      }
    }
    lazy val bytesInUse = bytes.length
    def updateIsEdge(otherIsEdge: Boolean) = CompositeId(colId, innerId, otherIsEdge, useHash)
    def updateUseHash(otherUseHash: Boolean) = CompositeId(colId, innerId, isEdge, otherUseHash)
    override def equals(obj: Any) = {
      obj match {
        case other: CompositeId => colId == other.colId && innerId == other.innerId
        case _ => false
      }
    }
  }

  /**
   * label + direction
   */

  object LabelWithDirection {
    val maxBytes = Bytes.toBytes(Int.MaxValue)
    def apply(compositeInt: Int): LabelWithDirection = {
      //      play.api.Logger.debug(s"CompositeInt: $compositeInt")

      val dir = compositeInt & ((1 << bitsForDir) - 1)
      val labelId = compositeInt >> bitsForDir
      LabelWithDirection(labelId, dir)
    }
  }
  case class LabelWithDirection(labelId: Int, dir: Int) {
    assert(dir < (1 << bitsForDir))
    assert(labelId < (Int.MaxValue >> bitsForDir))

    val labelBits = labelId << bitsForDir

    lazy val compositeInt = labelBits | dir
    lazy val bytes = Bytes.toBytes(compositeInt)
    lazy val dirToggled = LabelWithDirection(labelId, toggleDir(dir))
    def updateDir(newDir: Int) = LabelWithDirection(labelId, newDir)

  }

  object InnerVal {
    val defaultVal = InnerVal(None, None, None)
    val stringLenOffset = 7.toByte
    val maxStringLen = Byte.MaxValue - stringLenOffset
    val maxMetaByte = Byte.MaxValue
    val minMetaByte = 0.toByte
    /**
     * first byte encoding rule.
     * 0 => default
     * 1 => long
     * 2 => int
     * 3 => short
     * 4 => byte
     * 5 => true
     * 6 => false
     * 7 ~ 127 => string len + 7
     */
    val metaByte = Map("default" -> 0, "long" -> 1, "int" -> 2, "short" -> 3,
      "byte" -> 4, "true" -> 5, "false" -> 6).map {
        case (k, v) => (k, v.toByte)
      }
    val metaByteRev = metaByte.map { case (k, v) => (v.toByte, k) } ++ metaByte.map { case (k, v) => ((-v).toByte, k) }

    def maxIdVal(dataType: String) = {
      dataType match {
        case "string" => InnerVal.withStr((0 until (Byte.MaxValue - stringLenOffset)).map("~").mkString)
        case "long" => InnerVal.withLong(Long.MaxValue)
        case "bool" => InnerVal.withBoolean(true)
        case _ => throw IllegalDataTypeException(dataType)
      }
    }
    def minIdVal(dataType: String) = {
      dataType match {
        case "string" => InnerVal.withStr("")
        case "long" => InnerVal.withLong(1)
        case "bool" => InnerVal.withBoolean(false)
        case _ => throw IllegalDataTypeException(dataType)
      }
    }
    def apply(bytes: Array[Byte], offset: Int): InnerVal = {
      var pos = offset
      //      
      val len = bytes(pos)
      //      play.api.Logger.debug(s"${bytes(offset)}: ${bytes.toList.slice(pos, bytes.length)}")
      pos += 1

      val (longV, strV, boolV) = metaByteRev.get(len) match {
        case Some(s) =>
          s match {
            case "default" => (None, None, None)
            case "true" => (None, None, Some(true))
            case "false" => (None, None, Some(false))
            case "byte" =>
              val b = bytes(pos)
              val value = if (b >= 0) Byte.MaxValue - b else Byte.MinValue - b - 1
              (Some(value.toLong), None, None)
            case "short" =>
              val b = Bytes.toShort(bytes, pos, 2)
              val value = if (b >= 0) Short.MaxValue - b else Short.MinValue - b - 1
              (Some(value.toLong), None, None)
            case "int" =>
              val b = Bytes.toInt(bytes, pos, 4)
              val value = if (b >= 0) Int.MaxValue - b else Int.MinValue - b - 1
              (Some(value.toLong), None, None)
            case "long" =>
              val b = Bytes.toLong(bytes, pos, 8)
              val value = if (b >= 0) Long.MaxValue - b else Long.MinValue - b - 1
              (Some(value.toLong), None, None)
          }
        case _ => // string
          val strLen = len - stringLenOffset
          (None, Some(Bytes.toString(bytes, pos, strLen)), None)
      }

      InnerVal(longV, strV, boolV)
    }

    def withLong(l: Long): InnerVal = {
      //      if (l < 0) throw new IllegalDataRangeException("value should be >= 0")
      InnerVal(Some(l), None, None)
    }
    def withStr(s: String): InnerVal = {
      InnerVal(None, Some(s), None)
    }
    def withBoolean(b: Boolean): InnerVal = {
      InnerVal(None, None, Some(b))
    }
    /**
     * In natural order
     * -129, -128 , -2, -1 < 0 < 1, 2, 127, 128
     *
     * In byte order
     * 0 < 1, 2, 127, 128 < -129, -128, -2, -1
     *
     */
    def transform(l: Long): (Byte, Array[Byte]) = {
      if (Byte.MinValue < l && l <= Byte.MaxValue) {
        //        val value = if (l < 0) l - Byte.MinValue else l + Byte.MinValue
        val key = if (l >= 0) metaByte("byte") else -metaByte("byte")
        val value = if (l >= 0) Byte.MaxValue - l else Byte.MinValue - l - 1
        val valueBytes = Array.fill(1)(value.toByte)
        (key.toByte, valueBytes)
      } else if (Short.MinValue < l && l <= Short.MaxValue) {
        val key = if (l >= 0) metaByte("short") else -metaByte("short")
        val value = if (l >= 0) Short.MaxValue - l else Short.MinValue - l - 1
        val valueBytes = Bytes.toBytes(value.toShort)
        (key.toByte, valueBytes)
      } else if (Int.MinValue < l && l <= Int.MaxValue) {
        val key = if (l >= 0) metaByte("int") else -metaByte("int")
        val value = if (l >= 0) Int.MaxValue - l else Int.MinValue - l - 1
        val valueBytes = Bytes.toBytes(value.toInt)
        (key.toByte, valueBytes)
      } else if (Long.MinValue < l && l <= Long.MaxValue) {
        val key = if (l >= 0) metaByte("long") else -metaByte("long")
        val value = if (l >= 0) Long.MaxValue - l else Long.MinValue - l - 1
        val valueBytes = Bytes.toBytes(value.toLong)
        (key.toByte, valueBytes)
      } else {
        throw new Exception(s"InnerVal range is out: $l")
      }
    }
  }

  case class InnerVal(longV: Option[Long], strV: Option[String], boolV: Option[Boolean]) {
    import InnerVal._

    lazy val bytes = {
      val (meta, valBytes) = (longV, strV, boolV) match {
        case (None, None, None) =>
          (metaByte("default"), Array.empty[Byte])
        case (Some(l), None, None) =>
          transform(l)
        case (None, None, Some(b)) =>
          val meta = if (b) metaByte("true") else metaByte("false")
          (meta, Array.empty[Byte])
        case (None, Some(s), None) =>
          val sBytes = Bytes.toBytes(s)
          if (sBytes.length > maxStringLen) throw new IllegalDataTypeException(s"string in innerVal maxSize is $maxStringLen, given ${sBytes.length}")
          assert(sBytes.length <= maxStringLen)
          val meta = (stringLenOffset + sBytes.length).toByte
          (meta, sBytes)
        case _ => throw new IllegalDataTypeException("innerVal data type should be [long/string/bool]")
      }
      Bytes.add(Array.fill(1)(meta.toByte), valBytes)
    }

    //    lazy val bytesInUse = bytes.length
    lazy val isDefault = longV.isEmpty && strV.isEmpty && boolV.isEmpty
    lazy val value = (longV, strV, boolV) match {
      case (Some(l), None, None) => l
      case (None, Some(s), None) => s
      case (None, None, Some(b)) => b
      case _ => throw new Exception(s"InnerVal should be [long/integeer/short/byte/string/boolean]")
    }
    lazy val valueType = (longV, strV, boolV) match {
      case (Some(l), None, None) => "long"
      case (None, Some(s), None) => "string"
      case (None, None, Some(b)) => "boolean"
      case _ => throw new Exception(s"InnerVal should be [long/integeer/short/byte/string/boolean]")
    }
    override def toString(): String = {
      value.toString
    }

    def compare(other: InnerVal) = {
      (value, other.value) match {
        case (v1: Long, v2: Long) => v1.compare(v2)
        case (b1: Boolean, b2: Boolean) => b1.compare(b2)
        case (s1: String, s2: String) => s1.compare(s2)
        case _ => throw new Exception("Please check a type of the compare operands")
      }
    }
    def +(other: InnerVal) = {
      (value, other.value) match {
        case (v1: Long, v2: Long) => InnerVal.withLong(v1 + v2)
        case (b1: Boolean, b2: Boolean) => InnerVal.withBoolean(if (b2) !b1 else b1)
        case _ => throw new Exception("Please check a type of the incr operands")
      }
    }
    def <(other: InnerVal) = this.compare(other) < 0
    def <=(other: InnerVal) = this.compare(other) <= 0
    def >(other: InnerVal) = this.compare(other) > 0
    def >=(other: InnerVal) = this.compare(other) >= 0
    
  }
  object InnerValWithTs {
    def apply(bytes: Array[Byte], offset: Int): InnerValWithTs = {
      val innerVal = InnerVal(bytes, offset)
      var pos = offset + innerVal.bytes.length
      val ts = Bytes.toLong(bytes, pos, 8)
      InnerValWithTs(innerVal, ts)
    }
    def withLong(value: Long, ts: Long) = InnerValWithTs(InnerVal.withLong(value), ts)
    def withStr(value: String, ts: Long) = InnerValWithTs(InnerVal.withStr(value), ts)
    def withBoolean(value: Boolean, ts: Long) = InnerValWithTs(InnerVal.withBoolean(value), ts)
    def withInnerVal(value: InnerVal, ts: Long) = InnerValWithTs(value, ts)
  }
  case class InnerValWithTs(innerVal: InnerVal, ts: Long) {
    lazy val bytes = Bytes.add(innerVal.bytes, Bytes.toBytes(ts))
  }
  def propsToBytes(props: Seq[(Byte, InnerVal)]): Array[Byte] = {
    val len = props.length
    assert(len < Byte.MaxValue)
    var bytes = Array.fill(1)(len.toByte)
    for ((k, v) <- props) bytes = Bytes.add(bytes, v.bytes)

    //    Logger.debug(s"propsToBytes: $props => ${bytes.toList}")
    bytes
  }
  def propsToKeyValues(props: Seq[(Byte, InnerVal)]): Array[Byte] = {
    val len = props.length
    assert(len < Byte.MaxValue)
    var bytes = Array.fill(1)(len.toByte)
    for ((k, v) <- props) bytes = Bytes.add(bytes, Array.fill(1)(k), v.bytes)
    //    Logger.debug(s"propsToBytes: $props => ${bytes.toList}")
    bytes
  }
  def propsToKeyValuesWithTs(props: Seq[(Byte, InnerValWithTs)]): Array[Byte] = {
    val len = props.length
    assert(len < Byte.MaxValue)
    var bytes = Array.fill(1)(len.toByte)
    for ((k, v) <- props) bytes = Bytes.add(bytes, Array.fill(1)(k), v.bytes)
    //    Logger.debug(s"propsToBytes: $props => ${bytes.toList}")
    bytes
  }
  def bytesToKeyValues(bytes: Array[Byte], offset: Int): (Seq[(Byte, InnerVal)], Int) = {
    var pos = offset
    val len = bytes(pos)
    pos += 1
    val kvs = new ArrayBuffer[(Byte, InnerVal)]
    for (i <- (0 until len)) {
      val k = bytes(pos)
      pos += 1
      val v = InnerVal(bytes, pos)
      pos += v.bytes.length
      kvs += (k -> v)
    }
    val ret = (kvs.toList, pos)
    //    Logger.debug(s"bytesToProps: $ret")
    ret
  }
  def bytesToKeyValuesWithTs(bytes: Array[Byte], offset: Int): (Seq[(Byte, InnerValWithTs)], Int) = {
    var pos = offset
    val len = bytes(pos)
    pos += 1
    val kvs = new ArrayBuffer[(Byte, InnerValWithTs)]
    for (i <- (0 until len)) {
      val k = bytes(pos)
      pos += 1
      val v = InnerValWithTs(bytes, pos)
      pos += v.bytes.length
      kvs += (k -> v)
    }
    val ret = (kvs.toList, pos)
    //    Logger.debug(s"bytesToProps: $ret")
    ret
  }
  def bytesToProps(bytes: Array[Byte], offset: Int): (Seq[(Byte, InnerVal)], Int) = {
    var pos = offset
    val len = bytes(pos)
    pos += 1
    val kvs = new ArrayBuffer[(Byte, InnerVal)]
    for (i <- (0 until len)) {
      val k = LabelMeta.emptyValue
      val v = InnerVal(bytes, pos)
      pos += v.bytes.length
      kvs += (k -> v)
    }
    val ret = (kvs.toList, pos)
    //    Logger.debug(s"bytesToProps: $ret")
    ret
  }
  def labelOrderSeqWithIsInverted(labelOrderSeq: Byte, isInverted: Boolean): Array[Byte] = {
    assert(labelOrderSeq < (1 << 6))
    val byte = labelOrderSeq << 1 | (if (isInverted) 1 else 0)
    Array.fill(1)(byte.toByte)
  }
  def bytesToLabelIndexSeqWithIsInverted(bytes: Array[Byte], offset: Int): (Byte, Boolean) = {
    val byte = bytes(offset)
    val isInverted = if ((byte & 1) != 0) true else false
    val labelOrderSeq = byte >> 1
    (labelOrderSeq.toByte, isInverted)
  }
  /**
   * hbase specific classes
   */

  object VertexRowKey {
    val isEdge = false
    def apply(bytes: Array[Byte], offset: Int): VertexRowKey = {
      VertexRowKey(CompositeId(bytes, offset, isEdge, true))
    }
  }

  case class VertexRowKey(id: CompositeId) {
    lazy val bytes = id.bytes
  }

  object VertexQualifier {
    def apply(bytes: Array[Byte], offset: Int, len: Int): VertexQualifier = {
      VertexQualifier(bytes(offset))
    }
  }
  case class VertexQualifier(propKey: Byte) {
    lazy val bytes = Array.fill(1)(propKey)
  }

  object EdgeRowKey {
    val propMode = 0
    val isEdge = true
    def apply(bytes: Array[Byte], offset: Int): EdgeRowKey = {
      var pos = offset
      val copmositeId = CompositeId(bytes, pos, isEdge, true)
      pos += copmositeId.bytesInUse
      val labelWithDir = LabelWithDirection(Bytes.toInt(bytes, pos, 4))
      pos += 4
      val (labelOrderSeq, isInverted) = bytesToLabelIndexSeqWithIsInverted(bytes, pos)
      EdgeRowKey(copmositeId, labelWithDir, labelOrderSeq, isInverted)
    }
  }
  //TODO: split inverted table? cf?
  case class EdgeRowKey(srcVertexId: CompositeId, labelWithDir: LabelWithDirection, labelOrderSeq: Byte, isInverted: Boolean) {
    //    play.api.Logger.debug(s"$this")
    lazy val innerSrcVertexId = srcVertexId.updateUseHash(true)
    lazy val bytes = Bytes.add(innerSrcVertexId.bytes, labelWithDir.bytes, labelOrderSeqWithIsInverted(labelOrderSeq, isInverted))
  }

  object EdgeQualifier {
    val isEdge = true
    def apply(bytes: Array[Byte], offset: Int, len: Int): EdgeQualifier = {
      var pos = offset
      val op = bytes(offset + len - 1)

      val (props, tgtVertexId) = {
        val (props, endAt) = bytesToProps(bytes, pos)
        val tgtVertexId = CompositeId(bytes, endAt, true, false)
        (props, tgtVertexId)
      }
      EdgeQualifier(props, tgtVertexId, op)
    }
  }
  case class EdgeQualifier(props: Seq[(Byte, InnerVal)], tgtVertexId: CompositeId, op: Byte) {

    val opBytes = Array.fill(1)(op)
    val innerTgtVertexId = tgtVertexId.updateUseHash(false)
    lazy val propsBytes = propsToBytes(props)
    lazy val bytes = Bytes.add(propsBytes, innerTgtVertexId.bytes, opBytes)
    //TODO:
    def propsKVs(labelId: Int, labelOrderSeq: Byte): List[(Byte, InnerVal)] = {
      val filtered = props.filter(kv => kv._1 != LabelMeta.emptyValue)
      if (filtered.isEmpty) {
        val opt = for (index <- LabelIndex.findByLabelIdAndSeq(labelId, labelOrderSeq)) yield {
          val v = index.metaSeqs.zip(props.map(_._2))
          v
        }
        opt.getOrElse(List.empty[(Byte, InnerVal)])
      } else {
        filtered.toList
      }
    }
  }
  object EdgeQualifierInverted {
    def apply(bytes: Array[Byte], offset: Int): EdgeQualifierInverted = {
      val tgtVertexId = CompositeId(bytes, offset, true, false)
      EdgeQualifierInverted(tgtVertexId)
    }
  }
  case class EdgeQualifierInverted(tgtVertexId: CompositeId) {
    //    play.api.Logger.debug(s"$this")
    val innerTgtVertexId = tgtVertexId.updateUseHash(false)
    lazy val bytes = innerTgtVertexId.bytes
  }
  object EdgeValue {
    def apply(bytes: Array[Byte], offset: Int): EdgeValue = {
      val (props, endAt) = bytesToKeyValues(bytes, offset)
      EdgeValue(props)
    }
  }
  case class EdgeValue(props: Seq[(Byte, InnerVal)]) {
    lazy val bytes = propsToKeyValues(props)
  }
  object EdgeValueInverted {
    def apply(bytes: Array[Byte], offset: Int): EdgeValueInverted = {
      var pos = offset
      val op = bytes(pos)
      pos += 1
      val (props, endAt) = bytesToKeyValuesWithTs(bytes, pos)
      EdgeValueInverted(op, props)
    }
  }
  case class EdgeValueInverted(op: Byte, props: Seq[(Byte, InnerValWithTs)]) {
    lazy val bytes = Bytes.add(Array.fill(1)(op), propsToKeyValuesWithTs(props))
  }

}
