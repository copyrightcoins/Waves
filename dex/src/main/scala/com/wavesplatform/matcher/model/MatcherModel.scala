package com.wavesplatform.matcher.model

import cats.implicits._
import com.wavesplatform.account.Address
import com.wavesplatform.matcher.model.MatcherModel.Price
import com.wavesplatform.state.{Blockchain, Portfolio}
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.assets.exchange._
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.math.BigDecimal.RoundingMode

object MatcherModel {

  type Price = Long

  def getAssetDecimals(blockchain: Blockchain, asset: Asset): Int = {
    asset.fold(8) { issuedAsset =>
      blockchain
        .assetDescription(issuedAsset)
        .map(_.decimals)
        .getOrElse(throw new Exception("Can not get asset decimals since asset not found!"))
    }
  }

  def getPairDecimals(blockchain: Blockchain, pair: AssetPair): (Int, Int) =
    getAssetDecimals(blockchain, pair.amountAsset) -> getAssetDecimals(blockchain, pair.priceAsset)

  object Normalization {

    def normalizeAmountAndFee(value: Double, amountAssetDecimals: Int): Long =
      (BigDecimal(value) * BigDecimal(10).pow(amountAssetDecimals)).toLong

    def normalizePrice(value: Double, amountAssetDecimals: Int, priceAssetDecimals: Int): Long =
      (BigDecimal(value) * BigDecimal(10).pow(8 + priceAssetDecimals - amountAssetDecimals).toLongExact).toLong

    def normalizePrice(value: Double, blockchain: Blockchain, pair: AssetPair): Long = {
      val (amountAssetDecimals, priceAssetDecimals) = getPairDecimals(blockchain, pair)
      normalizePrice(value, amountAssetDecimals, priceAssetDecimals)
    }
  }

  object Denormalization {

    def denormalizeAmountAndFee(value: Long, amountAssetDecimals: Int): Double =
      (BigDecimal(value) / BigDecimal(10).pow(amountAssetDecimals)).toDouble

    def denormalizeAmountAndFee(value: Long, blockchain: Blockchain, pair: AssetPair): Double =
      denormalizeAmountAndFee(value, getAssetDecimals(blockchain, pair.amountAsset))

    def denormalizePrice(value: Long, amountAssetDecimals: Int, priceAssetDecimals: Int): Double =
      (BigDecimal(value) / BigDecimal(10).pow(8 + priceAssetDecimals - amountAssetDecimals).toLongExact).toDouble

    def denormalizePrice(value: Long, blockchain: Blockchain, pair: AssetPair): Double = {
      val (amountAssetDecimals, priceAssetDecimals) = getPairDecimals(blockchain, pair)
      denormalizePrice(value, amountAssetDecimals, priceAssetDecimals)
    }
  }

  def getCost(amount: Long, price: Long): Long = (BigDecimal(price) * amount / Order.PriceConstant).toLong
}

case class LevelAgg(amount: Long, price: Long)

sealed trait AcceptedOrder {

  def amount: Long // could be remaining or executed, see OrderExecuted
  def fee: Long    // same
  def order: Order

  def price: Price = order.price

  protected def rawSpentAmount: Long // Without correction

  def spentAmount: Long
  def receiveAmount: Long

  def isMarket: Boolean = this.fold(_ => false)(_ => true)
  def isLimit: Boolean  = this.fold(_ => true)(_ => false)

  def isBuyOrder: Boolean  = order.orderType == OrderType.BUY
  def isSellOrder: Boolean = order.orderType == OrderType.SELL

  def spentAsset: Asset = order.getSpendAssetId
  def rcvAsset: Asset   = order.getReceiveAssetId
  val feeAsset: Asset   = order.matcherFeeAssetId

  def requiredFee: Price                = if (feeAsset == rcvAsset) (fee - receiveAmount).max(0L) else fee
  def requiredBalance: Map[Asset, Long] = Map(spentAsset -> rawSpentAmount) |+| Map(feeAsset -> requiredFee)
  def reservableBalance: Map[Asset, Long]

  def availableBalanceBySpendableAssets(tradableBalance: Asset => Long): Map[Asset, Long] = {
    Set(spentAsset, feeAsset).map(asset => asset -> tradableBalance(asset)).toMap
  }

  def amountOfPriceAsset: Long  = (BigDecimal(amount) * price / Order.PriceConstant).setScale(0, RoundingMode.FLOOR).toLong
  def amountOfAmountAsset: Long = correctedAmountOfAmountAsset(amount, price)

  protected def executionAmount(counterPrice: Price): Long = correctedAmountOfAmountAsset(amount, counterPrice)

  def isValid: Boolean = isValid(price)
  def isValid(counterPrice: Price): Boolean =
    amount > 0 && amount >= minimalAmountOfAmountAssetByPrice(counterPrice) && amount < Order.MaxAmount && spentAmount > 0 && receiveAmount > 0

  private def minimalAmountOfAmountAssetByPrice(p: Long): Long = (BigDecimal(Order.PriceConstant) / p).setScale(0, RoundingMode.CEILING).toLong

  protected def correctedAmountOfAmountAsset(a: Long, p: Long): Long = {
    val settledTotal = (BigDecimal(p) * a / Order.PriceConstant).setScale(0, RoundingMode.FLOOR).toLong
    (BigDecimal(settledTotal) / p * Order.PriceConstant).setScale(0, RoundingMode.CEILING).toLong
  }

  def fold[A](fl: LimitOrder => A)(fm: MarketOrder => A): A = this match {
    case lo: LimitOrder  => fl(lo)
    case mo: MarketOrder => fm(mo)
  }
}

object AcceptedOrder {

  def partialFee(matcherFee: Long, totalAmount: Long, partialAmount: Long): Long = {
    // Should not round! It could lead to forks. See ExchangeTransactionDiff
    (BigInt(matcherFee) * partialAmount / totalAmount).toLong
  }

  /**
    * Returns executed amount obtained as a result of the match of submitted and counter orders
    *
    * For limit orders:
    *   executedAmount = matchedAmount = min(corrected amount of submitted order, corrected amount of counter order)
    *
    * For market orders:
    *
    *   Let x  = actual executed amount,
    *       a  = order amount,
    *       A  = available for spending,
    *       f  = order fee,
    *       f' = executed fee = f * (x / a),
    *       p' = counter price,
    *       c  = buy order cost (p' * x)
    *
    *   executedAmount = min(matchedAmount, x),
    *   most tricky cases occur when A < a, i.e. available for spending is not enough to execute submitted market order
    *
    *   BUY market orders:
    *
    *     1. Fee asset = spent asset
    *
    *       A = f' + c =
    *           f' + p' * x =
    *           f * (x / a) + p' * x =
    *           x * (p' + f / a)
    *
    *       x = (A * a) / (p' * a + f)
    *
    *     2. Other cases
    *
    *       A = c = p' * x
    *       x = A / p'
    *
    *   SELL market orders:
    *
    *     1. Fee asset = spent asset
    *
    *       A = f' + x =
    *           f * (x / a) + x =
    *           x (1 + f / a)
    *
    *       x = (A * a) / (a + f)
    *
    *     2. Other cases
    *
    *       A = x
    *
    */
  def executedAmount(submitted: AcceptedOrder, counter: LimitOrder): Long = {

    val matchedAmount                                 = math.min(submitted.executionAmount(counter.price), counter.amountOfAmountAsset)
    def minBetweenMatchedAmountAnd(value: Long): Long = math.min(matchedAmount, value)
    def correctByCounterPrice(value: Long): Long      = submitted.correctedAmountOfAmountAsset(value, counter.price)

    submitted.fold(_ => matchedAmount) {
      case mo if mo.isBuyOrder =>
        if (mo.spentAsset == mo.feeAsset)
          minBetweenMatchedAmountAnd {
            correctByCounterPrice {
              (BigDecimal(mo.availableForSpending) * mo.amount / (BigDecimal(counter.price) * mo.amount / Order.PriceConstant + mo.fee)).toLong
            }
          } else
          minBetweenMatchedAmountAnd {
            correctByCounterPrice {
              (BigDecimal(mo.availableForSpending) * Order.PriceConstant / counter.price).toLong
            }
          }
      case mo =>
        if (mo.spentAsset == mo.feeAsset)
          minBetweenMatchedAmountAnd { (BigDecimal(mo.availableForSpending) * mo.amount / (mo.amount + mo.fee)).toLong } else
          minBetweenMatchedAmountAnd { mo.availableForSpending }
    }
  }
}

sealed trait BuyOrder extends AcceptedOrder {
  def receiveAmount: Long  = amountOfAmountAsset
  def spentAmount: Long    = amountOfPriceAsset
  def rawSpentAmount: Long = amountOfPriceAsset
}

sealed trait SellOrder extends AcceptedOrder {
  def receiveAmount: Long  = amountOfPriceAsset
  def spentAmount: Long    = amountOfAmountAsset
  def rawSpentAmount: Long = amount
}

sealed trait LimitOrder extends AcceptedOrder {
  def partial(amount: Long, fee: Long): LimitOrder
  def reservableBalance: Map[Asset, Long] = requiredBalance
}

object LimitOrder {

  def apply(o: Order): LimitOrder = {

    val pf = AcceptedOrder.partialFee(o.matcherFee, o.amount, o.amount)

    o.orderType match {
      case OrderType.BUY  => BuyLimitOrder(o.amount, pf, o)
      case OrderType.SELL => SellLimitOrder(o.amount, pf, o)
    }
  }
}

case class BuyLimitOrder(amount: Long, fee: Long, order: Order) extends BuyOrder with LimitOrder {
  override def toString: String                       = s"BuyLimitOrder($amount,$fee,${order.id()})"
  def partial(amount: Long, fee: Long): BuyLimitOrder = copy(amount = amount, fee = fee)
}

case class SellLimitOrder(amount: Long, fee: Long, order: Order) extends SellOrder with LimitOrder {
  override def toString: String                        = s"SellLimitOrder($amount,$fee,${order.id()})"
  def partial(amount: Long, fee: Long): SellLimitOrder = copy(amount = amount, fee = fee)
}

sealed trait MarketOrder extends AcceptedOrder {
  val availableForSpending: Long
  def reservableBalance: Map[Asset, Long] = requiredBalance.updated(order.getSpendAssetId, availableForSpending)
  def partial(amount: Long, fee: Long, availableForSpending: Long): MarketOrder
}

object MarketOrder {

  def apply(o: Order, availableForSpending: Long): MarketOrder = {

    val pf = AcceptedOrder.partialFee(o.matcherFee, o.amount, o.amount)

    o.orderType match {
      case OrderType.BUY  => BuyMarketOrder(o.amount, pf, o, availableForSpending)
      case OrderType.SELL => SellMarketOrder(o.amount, pf, o, availableForSpending)
    }
  }

  def apply(o: Order, tradableBalance: Asset => Long): MarketOrder = {

    val pf                   = AcceptedOrder.partialFee(o.matcherFee, o.amount, o.amount)
    val availableForSpending = math.min(tradableBalance(o.getSpendAssetId), LimitOrder(o).requiredBalance(o.getSpendAssetId))

    o.orderType match {
      case OrderType.BUY  => BuyMarketOrder(o.amount, pf, o, availableForSpending)
      case OrderType.SELL => SellMarketOrder(o.amount, pf, o, availableForSpending)
    }
  }
}

case class BuyMarketOrder(amount: Long, fee: Long, order: Order, availableForSpending: Long) extends BuyOrder with MarketOrder {

  override def toString: String = s"BuyMarketOrder($amount,$fee,${order.id()},$availableForSpending)"

  def partial(amount: Long, fee: Long, availableForSpending: Long): BuyMarketOrder = {
    copy(amount = amount, fee = fee, availableForSpending = availableForSpending)
  }
}

case class SellMarketOrder(amount: Long, fee: Long, order: Order, availableForSpending: Long) extends SellOrder with MarketOrder {

  override def toString: String = s"SellMarketOrder($amount,$fee,${order.id()},$availableForSpending)"

  def partial(amount: Long, fee: Long, availableForSpendings: Long): SellMarketOrder = {
    copy(amount = amount, fee = fee, availableForSpending = availableForSpendings)
  }
}

sealed trait OrderStatus {
  def name: String
  def json: JsValue
}

object OrderStatus {

  sealed trait Final extends OrderStatus

  case object Accepted extends OrderStatus {
    val name           = "Accepted"
    def json: JsObject = Json.obj("status" -> name)
  }

  case object NotFound extends Final {
    val name           = "NotFound"
    def json: JsObject = Json.obj("status" -> name, "message" -> "The limit order is not found")
  }

  case class PartiallyFilled(filled: Long) extends OrderStatus {
    val name           = "PartiallyFilled"
    def json: JsObject = Json.obj("status" -> name, "filledAmount" -> filled)
  }

  case class Filled(filled: Long) extends Final {
    val name           = "Filled"
    def json: JsObject = Json.obj("status" -> name, "filledAmount" -> filled)
  }

  case class Cancelled(filled: Long) extends Final {
    val name           = "Cancelled"
    def json: JsObject = Json.obj("status" -> name, "filledAmount" -> filled)
  }

  def finalStatus(ao: AcceptedOrder, isSystemCancel: Boolean): Final = {
    val filledAmount = ao.order.amount - ao.amount
    if (isSystemCancel) Filled(filledAmount) else Cancelled(filledAmount)
  }
}

object Events {

  sealed trait Event

  case class OrderExecuted(submitted: AcceptedOrder, counter: LimitOrder, timestamp: Long) extends Event {

    lazy val executedAmount: Long             = AcceptedOrder.executedAmount(submitted, counter)
    lazy val executedAmountOfPriceAsset: Long = MatcherModel.getCost(executedAmount, counter.price) // TODO DISCUSS CORRECTION

    def counterRemainingAmount: Long = math.max(counter.amount - executedAmount, 0)
    def counterExecutedFee: Long     = AcceptedOrder.partialFee(counter.order.matcherFee, counter.order.amount, executedAmount)
    def counterRemainingFee: Long    = math.max(counter.fee - counterExecutedFee, 0)
    def counterRemaining: LimitOrder = counter.partial(amount = counterRemainingAmount, fee = counterRemainingFee)

    def submittedRemainingAmount: Long = math.max(submitted.amount - executedAmount, 0)
    def submittedExecutedFee: Long     = AcceptedOrder.partialFee(submitted.order.matcherFee, submitted.order.amount, executedAmount)
    def submittedRemainingFee: Long    = math.max(submitted.fee - submittedExecutedFee, 0)

    def submittedMarketRemaining(submittedMarketOrder: MarketOrder): MarketOrder = {

      val spentAmount = if (submitted.isSellOrder) executedAmount else executedAmountOfPriceAsset
      val spentFee    = if (submitted.feeAsset == submitted.spentAsset) submittedExecutedFee else 0L

      submittedMarketOrder.partial(
        amount = submittedRemainingAmount,
        fee = submittedRemainingFee,
        availableForSpending = submittedMarketOrder.availableForSpending - spentAmount - spentFee
      )
    }

    def submittedLimitRemaining(submittedLimitOrder: LimitOrder): LimitOrder = {
      submittedLimitOrder.partial(amount = submittedRemainingAmount, fee = submittedRemainingFee)
    }

    def submittedRemaining: AcceptedOrder = submitted.fold[AcceptedOrder] { submittedLimitRemaining } { submittedMarketRemaining }
  }

  case class OrderAdded(order: LimitOrder, timestamp: Long)                                        extends Event
  case class OrderCanceled(acceptedOrder: AcceptedOrder, isSystemCancel: Boolean, timestamp: Long) extends Event
  case class ExchangeTransactionCreated(tx: ExchangeTransaction)

  case class BalanceChanged(changes: Map[Address, BalanceChanged.Changes]) {
    def isEmpty: Boolean = changes.isEmpty
  }

  object BalanceChanged {
    val empty: BalanceChanged = BalanceChanged(Map.empty)
    case class Changes(updatedPortfolio: Portfolio, changedAssets: Set[Option[Asset]])
  }
}