package edu.cmu.ml.rtw.pra.experiments

import java.io.FileWriter

import edu.cmu.ml.rtw.pra.config.PraConfig
import edu.cmu.ml.rtw.pra.graphs.Graph
import edu.cmu.ml.rtw.pra.graphs.GraphBuilder
import edu.cmu.ml.rtw.users.matt.util.Dictionary
import edu.cmu.ml.rtw.users.matt.util.FileUtil

import scala.collection.JavaConverters._

// Not a case class, because we really don't want equality on source/target/isPositive.  We need
// equality to only look at object identity, because the source and target Ints might be with
// respect to different dictionaries.
class Instance(val source: Int, val target: Int, val isPositive: Boolean)

/**
 * A collection of positive and negative (source, target) pairs.
 */
class Dataset(
    val instances: Seq[Instance],
    val config: PraConfig = null,
    val instanceGraphs: Option[Seq[Graph]] = None,
    val fileUtil: FileUtil = new FileUtil) {
  def getPosAndNeg() = instances.partition(_.isPositive)
  def getPositiveInstances() = instances.filter(_.isPositive)
  def getNegativeInstances() = instances.filter(!_.isPositive)
  def getAllSources() = instances.map(_.source).toSet
  def getAllTargets() = instances.map(_.target).toSet

  def getSourceMap() = _getSourceMap(instances)
  def getPositiveSourceMap() = _getSourceMap(getPositiveInstances)
  def getNegativeSourceMap() = _getSourceMap(getNegativeInstances)

  def _getSourceMap(instance_list: Seq[Instance]) = instance_list.groupBy(_.source)
    .mapValues(_.map(_.target).toSet)

  def getGraphForInstance(instance: Instance): Graph = {
    val index = instances.indexOf(instance)
    getGraphForInstance(index)
  }

  lazy val sharedGraph: Graph = {
    if (config == null) {
      throw new IllegalStateException("If you want to use getGraphForInstance with a shared graph,"
        + " you need to create the dataset with a PraConfig!")
    }
    loadGraph(config.graph, config.nodeDict.getNextIndex)
  }

  def loadGraph(graphFile: String, numNodes: Int): Graph = {
    println(s"Loading graph")
    val graphBuilder = new GraphBuilder(numNodes)
    val lines = fileUtil.readLinesFromFile(graphFile).asScala
    val reader = fileUtil.getBufferedReader(graphFile)
    var line: String = null
    while ({ line = reader.readLine; line != null }) {
      val fields = line.split("\t")
      val source = fields(0).toInt
      val target = fields(1).toInt
      val relation = fields(2).toInt
      graphBuilder.addEdge(source, target, relation)
    }
    graphBuilder.build
  }

  def getGraphForInstance(index: Int): Graph = {
    instanceGraphs match {
      case None => {
        sharedGraph
      }
      case Some(graphs) => {
        graphs(index)
      }
    }
  }

  /**
   * Takes the examples in this dataset and splits it into two datasets, where the first has
   * percent of the data.  Modulo rounding errors, the ratio of positive to negative examples
   * will be the same in both resultant datasets as it is in the original dataset.
   */
  def splitData(percent: Double): (Dataset, Dataset) = {
    import scala.util.Random
    val random = new Random
    val (positiveInstances, negativeInstances) = getPosAndNeg()

    val numPositiveTraining = (percent * positiveInstances.size).toInt
    random.shuffle(positiveInstances)
    val (trainingPositive, testingPositive) = positiveInstances.splitAt(numPositiveTraining)

    val numNegativeTraining = (percent * negativeInstances.size).toInt
    random.shuffle(negativeInstances)
    val (trainingNegative, testingNegative) = negativeInstances.splitAt(numNegativeTraining)

    val training = new Dataset(trainingPositive ++ trainingNegative, config)
    val testing = new Dataset(testingPositive ++ testingNegative, config)
    (training, testing)
  }

  def instancesToStrings(nodeDict: Dictionary): Seq[String] = {
    instances.map(instanceToString(nodeDict))
  }

  def instanceToString(nodeDict: Dictionary)(instance: Instance): String = {
    val pos = if (instance.isPositive) 1 else -1
    if (nodeDict == null) {
      s"${instance.source}\t${instance.target}\t$pos"
    } else {
      s"${nodeDict.getString(instance.source)}\t${nodeDict.getString(instance.target)}\t$pos"
    }
  }

  def merge(other: Dataset) = new Dataset(instances ++ other.instances, config)
}

object Dataset {
  /**
   * Reads a Dataset from a file.  The format is assumed to be TSV with the following columns:
   *
   * source [tab] target
   *
   * where all examples are assumed to be positive, or
   *
   * source [tab] target [tab] {1,-1}
   *
   * where a 1 in the last column indicates a positive example and a -1 indicates a negative
   * example.
   *
   * The source and target columns can either be strings, with a dictionary provided to convert the
   * strings to integers, or integers that will be parsed.  The logic here doesn't check if the
   * entry is an integer, it just checks if the dictionary is null or not.
   */
  def fromFile(filename: String, config: PraConfig, fileUtil: FileUtil = new FileUtil): Dataset = {
    val lines = fileUtil.readLinesFromFile(filename).asScala
    if (lines(0).split("\t").size == 4) {
      val results = lines.par.map(lineToInstanceAndGraph).seq
      val instances = results.map(_._1).toSeq
      val graphs = Some(results.map(_._2).toSeq)
      new Dataset(instances, config, graphs, fileUtil)
    } else {
      val instances = lines.par.map(lineToInstance(config.nodeDict)).seq
      new Dataset(instances, config, None, fileUtil)
    }
  }

  def lineToInstance(dict: Dictionary)(line: String): Instance = {
    val fields = line.split("\t")
    val isPositive = if (fields.size == 2) true else fields(2).toInt == 1
    val source = if (dict == null) fields(0).toInt else dict.getIndex(fields(0))
    val target = if (dict == null) fields(1).toInt else dict.getIndex(fields(1))
    new Instance(source, target, isPositive)
  }

  def lineToInstanceAndGraph(line: String): (Instance, Graph) = {
    val instanceFields = line.split("\t")
    val instanceSource = instanceFields(0)
    val instanceTarget = instanceFields(1)
    val isPositive = instanceFields(2).toInt == 1
    val graphString = instanceFields(3)
    val graphBuilder = new GraphBuilder()
    val graphEdges = graphString.split(" ### ")
    for (edge <- graphEdges) {
      val fields = edge.split("\\^,\\^")
      println(fields.toList)
      val source = fields(0)
      val target = fields(1)
      val relation = fields(2)
      graphBuilder.addEdge(source, target, relation)
    }
    val graph = graphBuilder.build()
    // This isn't ideal, but the Ints in the Instance object need to be with respect to the graph.
    // It might be a bit cleaner to have each Instance have a pointer to the graph that it
    // corresponds to, but that would mean that in the single-graph case, I have to construct the
    // graph before reading the Dataset, and can't just load it lazily...  It's a bit messy either
    // way, so I'm going to keep the lazy loading and just grab indices from the instance graph's
    // nodeDict here.
    val sourceId = graph.nodeDict.getIndex(instanceSource)
    val targetId = graph.nodeDict.getIndex(instanceTarget)
    (new Instance(sourceId, targetId, isPositive), graph)
  }

}