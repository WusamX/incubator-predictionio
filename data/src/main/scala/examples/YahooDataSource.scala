package io.prediction.data.examples

import io.prediction.data.storage.Event
import io.prediction.data.storage.Events
import io.prediction.data.storage.Storage
import io.prediction.data.view.LBatchView
import io.prediction.data.storage.DataMap

import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import com.github.nscala_time.time.Imports._

import scala.collection.mutable.{ Map => MMap }

case class HistoricalData(
  val ticker: String,
  val timeIndex: Array[DateTime],
  val close: Array[Double],
  val adjClose: Array[Double],
  val adjReturn: Array[Double],
  val volume: Array[Double]) {

  override
  def toString(): String = {
    s"HistoricalData($ticker, ${timeIndex.head}, ${timeIndex.last})"
  }
}

class YahooDataSource(val params: YahooDataSource.Params) {
  @transient lazy val batchView = new LBatchView(
    params.appId, params.startTime, params.untilTime)
    
  val timezone = DateTimeZone.forID("US/Eastern")

  def merge(intermediate: YahooDataSource.Intermediate, e: Event)
  : YahooDataSource.Intermediate = {
    val dm: DataMap = e.properties     

    val dailyMap = intermediate.dailyMap
    // TODO: Check ticker in intermediate
    
    val closeList: Array[Double] = dm.get[Array[Double]]("close")
    val adjCloseList: Array[Double] = dm.get[Array[Double]]("adjclose")
    val volumeList: Array[Double] = dm.get[Array[Double]]("volume")
        
    val tList: Array[DateTime] = dm.get[Array[Long]]("t")
      .map(t => new DateTime(t * 1000, timezone))

    tList.zipWithIndex.drop(1).foreach { case (t, idx) => {
      val adjReturn = (adjCloseList(idx) / adjCloseList(idx - 1)) - 1

      val daily = YahooDataSource.Daily(
        close = closeList(idx),
        adjClose = adjCloseList(idx),
        adjReturn = adjReturn,
        volume = volumeList(idx),
        prevDate = tList(idx - 1))

      dailyMap += (t -> daily)
    }}
   
    YahooDataSource.Intermediate(ticker = e.entityId, dailyMap = dailyMap)
  }

  def finalize(intermediate: YahooDataSource.Intermediate): HistoricalData = {
    val dailyMap = intermediate.dailyMap
    val ticker = intermediate.ticker

    val timeIndex: Array[DateTime] = dailyMap.keys.toArray.sortBy(identity)
    (1 until timeIndex.size).foreach { idx => {
      require(dailyMap(timeIndex(idx)).prevDate == timeIndex(idx - 1),
        s"Time must be continuous. " +
        s"For ticker $ticker, there is a gap between " +
        s"${timeIndex(idx - 1)} and ${timeIndex(idx)}. " + 
        s"Please import data to cover the gap or use a shorter range.")
    }}

    HistoricalData(
      ticker = ticker, 
      timeIndex = timeIndex,
      close = timeIndex.map(t => dailyMap(t).close),
      adjClose = timeIndex.map(t => dailyMap(t).adjClose),
      adjReturn = timeIndex.map(t => dailyMap(t).adjReturn),
      volume = timeIndex.map(t => dailyMap(t).volume))
  }
  

  def read() {
    val predicate = (e: Event) => (e.entityType == "yahoo")
    val map: (Event => DataMap) = (e: Event) => (e.properties)

    val tickerMap: Map[String, HistoricalData] = batchView
      .aggregateByEntityOrdered(
        predicate,
        YahooDataSource.Intermediate(),
        merge)
      .mapValues(finalize)

    tickerMap.foreach { case (ticker, data) => {
      println(ticker)
      println(data)
    }}
  }
}

object YahooDataSource {
  case class Params(
    val appId: Int,
    val startTime: Option[DateTime] = None,
    val untilTime: Option[DateTime] = None
  )

  case class Daily(
    val close: Double,
    val adjClose: Double,
    val adjReturn: Double,
    val volume: Double,
    // prevDate is used to verify continuity
    val prevDate: DateTime)

  case class Intermediate(
    val ticker: String = "", 
    val dailyMap: MMap[DateTime, Daily] = MMap[DateTime, Daily]())

  def main(args: Array[String]) {
    val params = Params(
      appId = 1,
      untilTime = Some(new DateTime(2014, 5, 1, 0, 0)))
      //untilTime = None)

    val ds = new YahooDataSource(params)
    ds.read
  }
}
