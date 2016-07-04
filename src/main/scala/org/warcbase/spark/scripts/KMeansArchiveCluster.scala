package org.warcbase.spark.scripts

import org.apache.spark.mllib.clustering.{OnlineLDAOptimizer, KMeansModel, LDA}
import org.apache.spark.mllib.feature.HashingTF
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.rdd.RDD
import org.apache.spark.{AccumulatorParam, HashPartitioner, SparkContext}
import org.warcbase.spark.utils.RddAccumulator

import scala.collection.mutable.{ArrayBuffer, HashMap, ListBuffer}


class KMeansArchiveCluster(clusters: KMeansModel, tfidf: RDD[Vector], lemmatized: RDD[Seq[String]],
                           rec: RDD[(String)]) extends Serializable{
  val hashingTF = new HashingTF()
  lazy val allWords = lemmatized.flatMap(seq => seq.map(f=>f)).persist()
  lazy val hashIndexToTerm = allWords.map(s=>(hashingTF.indexOf(s), s)).distinct().cache()
  lazy val clusterRdds = getClusterRdds()

  implicit object ArrayAccumulator extends AccumulatorParam[ArrayBuffer[(Int, (List[String], List[String]))]] {
    def zero(m: ArrayBuffer[(Int, (List[String], List[String]))]) =
      new ArrayBuffer[(Int, (List[String], List[String]))]
    def addInPlace(m1: ArrayBuffer[(Int, (List[String], List[String]))]
                   , m2: ArrayBuffer[(Int, (List[String], List[String]))]) = m1 ++ m2
  }

  private def getClusterRdds() = {
    val rdds = new ArrayBuffer[(Int, RDD[(Vector, String)])]
    val merged = tfidf.zip(rec).map(r=>(r._1, r._2))
    for (i <- 0 to clusters.k-1) {
      rdds += Pair(i, merged.filter(v => clusters.predict(v._1) == i).persist())
    }
    println(rdds(1))
    rdds
  }

  def getSampleDocs(sc: SparkContext, numDocs: Int=10): RDD[(Int, String)] ={
    val accum = sc.accumulator(sc.emptyRDD: RDD[(Int, String)])(new RddAccumulator[(Int, String)])
    clusterRdds.par.foreach(c => {
      val cluster = c._2
      val p = clusters.clusterCenters(c._1)
      val docs = cluster.map(r => (Vectors.sqdist(p, r._1), r._2)).takeOrdered(numDocs)(Ordering[Double].on(x => x._1))
      accum += sc.parallelize(docs).map(r => (c._1, r._2))
    })
    accum.value
  }

  def computeLDA(output: String, sc: SparkContext, numTopics: Int = 20, numWordsPerTopic: Int = 20, maxIteration: Int = 2) = {
    val accum = sc.accumulator(sc.emptyRDD[(Int, (Long, Seq[String], Double))]:
      RDD[(Int, (Long, Seq[String], Double))])(new RddAccumulator[(Int, (Long, Seq[String], Double))])
    clusterRdds.par.foreach(c => {
      val cluster = tfidf.persist()
      println(s"cluster size ${cluster.count()}")
      val corpus = cluster.zipWithIndex.map(_.swap).cache()
      val ldaModel = new LDA() //.setOptimizer(new OnlineLDAOptimizer().setMiniBatchFraction(0.05 + 1.0 / cluster.count()))
          .setMaxIterations(maxIteration).setK(numTopics).run(corpus)
      val topicArr:Array[(Array[Int], Array[Double])] = ldaModel.describeTopics(numWordsPerTopic)
      val topicRdd:RDD[(Array[Seq[String]], Array[Double])] = sc.parallelize(
        topicArr.map(topic => (topic._1.map(index => hashIndexToTerm.lookup(index)), topic._2))).cache()
      val topicWords = topicRdd.zipWithIndex().map(_.swap).flatMap(r => r._2._1.map(word => (r._1, word)))
      val topicScores = topicRdd.flatMap(r => r._2.map(word=>word))
      val topics = topicWords.zip(topicScores)
      accum += topics.map(r => (0, (r._1._1, r._1._2.toList, r._2)))
    })
    accum.value.partitionBy(new HashPartitioner(clusters.k)).map(_._2).mapPartitions(r=>{
      val dict:HashMap[Long, ListBuffer[String]] = new HashMap()
      r.map(r=>(r._1, r._2)).foreach(tuple=> {
        val list = dict.getOrElse(tuple._1, new ListBuffer[String]())
        list += tuple._2.mkString(start="(", ",", end=")")
        dict.put(tuple._1, list)
      })
      dict.toSeq.sortBy(x=>x._1).map(x=> "Topic " + x._1 + ": " + x._2.mkString(",")).toIterator
    }).saveAsTextFile(output)
    this
  }

  def topNWords(output: String, sc: SparkContext, limit: Int = 20, numDocs: Int = 20) = {
    val accum = sc.accumulator(new ArrayBuffer[(Int, (List[String], List[String]))])(ArrayAccumulator)
    clusterRdds.par.foreach(c => {
      val cluster = c._2
      println(s"cluster size ${cluster.count()}")
      val clusterNum = c._1
      val clusterCenter = clusters.clusterCenters(clusterNum)
      val docs = cluster.map(r => (Vectors.sqdist(clusterCenter, r._1), r._2)).takeOrdered(numDocs)(Ordering[Double].on(x => x._1)).map(_._2)

      val topWords = sc.parallelize(clusterCenter.toArray).zipWithIndex.takeOrdered(limit)(Ordering[Double].reverse.on(x=>x._1))
        .map{ case (k, i) => hashIndexToTerm.lookup(i.toInt).mkString(",")}
      accum += ArrayBuffer((clusterNum, (topWords.toList, docs.toList)))//.map(x=>x.mkString(","))))})
    })
    val result = sc.parallelize(accum.value).partitionBy(new HashPartitioner(clusters.k)).map(_._2).saveAsTextFile(output)
    this
  }

  @Deprecated
  def saveSampleDocs(output: String, sc: SparkContext, numDocs: Int=10) = {
    getSampleDocs(sc, numDocs).partitionBy(new HashPartitioner(clusters.k)).map(r=>r._2).saveAsTextFile(output)
    this
  }
}

