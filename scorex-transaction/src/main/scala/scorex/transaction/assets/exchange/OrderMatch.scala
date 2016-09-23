package scorex.transaction.assets.exchange

import com.google.common.primitives.{Ints, Longs}
import play.api.libs.json.{JsObject, Json}
import scorex.crypto.EllipticCurveImpl
import scorex.crypto.encode.Base58
import scorex.crypto.hash.FastCryptographicHash
import scorex.serialization.{BytesSerializable, Deser}
import scorex.transaction.{AssetAcc, BalanceChange, Transaction}

import scala.util.Try

/**
  * Transaction with matched orders generated by Matcher service
  */
case class OrderMatch(order1: Order, order2: Order, price: Long, amount: Long, matcherFee: Long, fee: Long,
                      timestamp: Long, signature: Array[Byte]) extends Transaction with BytesSerializable {

  override val id: Array[Byte] = FastCryptographicHash(toSign)

  def isValid(previousMatches: Seq[OrderMatch]): Boolean = {
    lazy val order1Transactions = previousMatches.filter { om =>
      (om.order1.signature sameElements order1.signature) || (om.order2.signature sameElements order1.signature)
    }
    lazy val order2Transactions = previousMatches.filter { om =>
      (om.order1.signature sameElements order2.signature) || (om.order2.signature sameElements order2.signature)
    }

    lazy val ordersMatches: Boolean = {
      lazy val priceMatches = if (order1.priceAssetId sameElements order1.receiveAssetId) order1.price >= order2.price
      else order2.price <= order1.price
      (order1.matcher.address == order2.matcher.address) &&
        (order1.spendAssetId sameElements order2.receiveAssetId) &&
        (order2.spendAssetId sameElements order1.receiveAssetId) && priceMatches
    }.ensuring(a => !a || (order1.priceAssetId sameElements order2.priceAssetId))
    lazy val priceIsValid: Boolean = (order1.price == price) || (order2.price == price)
    lazy val amountIsValid: Boolean = {
      val order1Total = order1Transactions.map(_.amount).sum + amount
      val order2Total = order2Transactions.map(_.amount).sum + amount
      (order1Total <= order1.amount) && (order2Total <= order2.amount)
    }
    lazy val matcherFeeIsValid: Boolean = {
      //matcher takes part of fee = part of matched order
      matcherFee == (order1.matcherFee * amount / order1.amount + order2.matcherFee * amount / order2.amount)
    }
    lazy val matcherSignatureIsValid: Boolean =
      EllipticCurveImpl.verify(signature, toSign, order1.matcher.publicKey)

    fee > 0 && amount > 0 && price > 0 && ordersMatches && order1.isValid(timestamp) && order2.isValid(timestamp) &&
      priceIsValid && amountIsValid && matcherFeeIsValid && matcherSignatureIsValid
  }

  lazy val toSign: Array[Byte] = Ints.toByteArray(order1.bytes.length) ++ Ints.toByteArray(order2.bytes.length) ++
    order1.bytes ++ order2.bytes ++ Longs.toByteArray(price) ++ Longs.toByteArray(amount) ++
    Longs.toByteArray(matcherFee) ++ Longs.toByteArray(fee) ++ Longs.toByteArray(timestamp)

  override def bytes: Array[Byte] = toSign ++ signature

  override def json: JsObject = Json.obj(
    "order1" -> order1.json,
    "order2" -> order2.json,
    "price" -> price,
    "amount" -> amount,
    "matcherFee" -> matcherFee,
    "fee" -> fee,
    "timestamp" -> timestamp,
    "signature" -> Base58.encode(signature)
  )

  override def balanceChanges(): Seq[BalanceChange] = {

    val matcherChange = Seq(BalanceChange(AssetAcc(order1.matcher, None), matcherFee - fee))
    val o1feeChange = Seq(BalanceChange(AssetAcc(order1.sender, None), order1.matcherFee * amount / order1.amount))
    val o2feeChange = Seq(BalanceChange(AssetAcc(order2.sender, None), order2.matcherFee * amount / order2.amount))

    val exchange = if (order1.priceAssetId sameElements order1.spendAssetId) {
      Seq(
        (order1.sender, (order1.receiveAssetId, amount)),
        (order2.sender, (order1.receiveAssetId, -amount)),
        (order1.sender, (order1.spendAssetId, -amount * price / PriceConstant)),
        (order2.sender, (order1.spendAssetId, +amount * price / PriceConstant))
      )
    } else {
      Seq(
        (order1.sender, (order1.spendAssetId, amount)),
        (order2.sender, (order1.spendAssetId, -amount)),
        (order1.sender, (order1.receiveAssetId, -amount * price / PriceConstant)),
        (order2.sender, (order1.receiveAssetId, +amount * price / PriceConstant))
      )
    }
    o1feeChange ++ o2feeChange ++ matcherChange ++
      exchange.map(c => BalanceChange(AssetAcc(c._1, Some(c._2._1)), c._2._2))
  }
}

object OrderMatch extends Deser[OrderMatch] {
  override def parseBytes(bytes: Array[Byte]): Try[OrderMatch] = Try {
    val o1Size = Ints.fromByteArray(bytes.slice(0, 4))
    val o2Size = Ints.fromByteArray(bytes.slice(4, 8))
    val o1 = Order.parseBytes(bytes.slice(8, 8 + o1Size)).get
    val o2 = Order.parseBytes(bytes.slice(8 + o1Size, 8 + o1Size + o2Size)).get
    val s = 8 + o1Size + o2Size
    val price = Longs.fromByteArray(bytes.slice(s, s + 8))
    val amount = Longs.fromByteArray(bytes.slice(s + 8, s + 16))
    val matcherFee = Longs.fromByteArray(bytes.slice(s + 16, s + 24))
    val fee = Longs.fromByteArray(bytes.slice(s + 24, s + 32))
    val timestamp = Longs.fromByteArray(bytes.slice(s + 32, s + 40))
    val signature = bytes.slice(s + 40, bytes.length)
    OrderMatch(o1, o2, price, amount, matcherFee, fee, timestamp, signature)
  }
}
