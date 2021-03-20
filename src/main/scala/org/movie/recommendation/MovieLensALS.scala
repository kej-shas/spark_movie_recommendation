package org.movie.recommendation

import java.io.File

import org.apache.log4j.{Level, Logger}
import org.apache.spark.mllib.recommendation.{ALS, MatrixFactorizationModel, Rating}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

import scala.io.Source

object MovieLensALS {
    
    def main(args: Array[String]) {
        // 屏蔽不必要的日志显示在终端上
        Logger.getLogger("org.apache.spark").setLevel(Level.ERROR)
        Logger.getLogger("org.eclipse.jetty.server").setLevel(Level.OFF)
        
        if (args.length != 5) {
            println("Usage: /path/to/spark/bin/spark-submit --driver-memory 2g --class week7.MovieLensALS " +
                "week7.jar movieLensHomeDir personalRatingsFile bestRank bestLambda bestNumiter")
            sys.exit(1)
        }
        
        // 设置运行环境
        val conf = new SparkConf().setAppName("MovieLensALS").setMaster("local[*]")
        val sc = new SparkContext(conf)
        
        // 装载参数二，即用户评分，该评分由评分器生成
        val myRatings = loadRatings(args(1))
        val myRatingsRDD = sc.parallelize(myRatings, 1)
        
        // 样本数据目录
        val movieLensHomeDir = args(0)
        
        // 装载样本评分数据，其中最后一列Timestamp取除10的余数作为key，Rating为值,即(Int,Rating)
        //ratings.dat原始数据：用户编号、电影编号、评分、评分时间戳
        val ratings = sc.textFile(new File(movieLensHomeDir, "ratings.dat").toString).map { line =>
            val fields = line.split("::")
            (fields(3).toLong % 10, Rating(fields(0).toInt, fields(1).toInt, fields(2).toDouble))
        }
        
        //装载电影目录对照表（电影ID->电影标题）
        //movies.dat原始数据：电影编号、电影名称、电影类别
        val movies = sc.textFile(new File(movieLensHomeDir, "movies.dat").toString).map { line =>
            val fields = line.split("::")
            (fields(0).toInt, fields(1).toString())
        }.collect().toMap
        
        val numRatings = ratings.count()
        
        val numUsers = ratings.map(_._2.user).distinct().count()
        
        val numMovies = ratings.map(_._2.product).distinct().count()
        
        // 将样本评分表以key值切分成3个部分，分别用于训练 (60%，并加入用户评分), 校验 (20%), and 测试 (20%)
        // 该数据在计算过程中要多次应用到，所以cache到内存
        val numPartitions = 4
        
        // training训练样本数据
        val training = ratings.filter(x => x._1 < 6) //取评分时间除10的余数后值小于6的作为训练样本
            .values
            .union(myRatingsRDD) //注意ratings是(Int,Rating)，取value即可
            .repartition(numPartitions)
            .cache()
        
        // validation校验样本数据
        val validation = ratings.filter(x => x._1 >= 6 && x._1 < 8) //取评分时间除10的余数后值大于等于6且小于8分的作为校验样本
            .values
            .repartition(numPartitions)
            .cache()
        
        // test测试样本数据
        val test = ratings.filter(x => x._1 >= 8).values.cache() //取评分时间除10的余数后值大于等于8分的作为测试样本
        
        val numTraining = training.count()
        
        val numValidation = validation.count()
        
        val numTest = test.count()
        
        // 训练不同参数下的模型，并在校验集中验证，获取最佳参数下的模型
        val ranks = List(8, 12) //模型中隐语义因子的个数
        val lambdas = List(0.1, 10.0) //是ALS的正则化参数
        val numIters = List(10, 20) //迭代次数
        
        var bestModel: Option[MatrixFactorizationModel] = None //最好的模型
        var bestValidationRmse = Double.MaxValue //最好的校验均方根误差
        var bestRank = args(2).toInt //最好的隐语义因子的个数
        var bestLambda = args(3).toDouble //最好的ALS正则化参数
        var bestNumIter = args(4).toInt //最好的迭代次数
        //val model = ALS.train(training, bestRank, bestNumIter, bestLambda) //如果是从外部传入参数，则使用该语句训练模型
        //如果不使用外部传入的参数，而是使用上面定义的ranks、lambdas和numIters的列表值进行模型训练，则使用下面的for循环语句训练模型
        for (rank <- ranks; lambda <- lambdas; numIter <- numIters) {
            val model = ALS.train(training, rank, numIter, lambda) //训练样本、隐语义因子的个数、迭代次数、ALS的正则化参数
            // model训练模型
            //输入训练模型、校验样本、校验个数
            val validationRmse = computeRmse(model, validation, numValidation) // 校验模型结果
            
            if (validationRmse < bestValidationRmse) {
                bestModel = Some(model)
                bestValidationRmse = validationRmse
                bestRank = rank
                bestLambda = lambda
                bestNumIter = numIter
                
            }
        }
        
        // 用最佳模型预测测试集的评分，并计算和实际评分之间的均方根误差
        val testRmse = computeRmse(bestModel.get, test, numTest)
        
        //创建一个naive基线和最好的模型比较
        val meanRating = training.union(validation).map(_.rating).mean
        val baselineRmse =
            math.sqrt(test.map(x => (meanRating - x.rating) * (meanRating - x.rating)).mean)
        //提高了基线的最佳模型
        val improvement = (baselineRmse - testRmse) / baselineRmse * 100
        
        // 推荐前5部最感兴趣的电影，注意要剔除用户已经评分的电影
        val myRatedMovieIds = myRatings.map(_.product).toSet
        
        val candidates = sc.parallelize(movies.keys.filter(!myRatedMovieIds.contains(_)).toSeq)
        
        val recommendations = bestModel.get
            .predict(candidates.map((1, _)))
            .collect()
            .sortBy(-_.rating)
            .take(10)
        
        var i = 1
        println("Movies recommended for you(用户ID：推荐电影ID：推荐分数：推荐电影名称):")
        recommendations.foreach { r =>
            println(r.user + ":" + r.product + ":" + r.rating + ":" + movies(r.product))
            i += 1
        }
        
        val recommendations2 = bestModel.get
            .predict(candidates.map((2, _)))
            .collect()
            .sortBy(-_.rating)
            .take(10)
        var i2 = 1
        recommendations2.foreach { r =>
            println(r.user + ":" + r.product + ":" + r.rating + ":" + movies(r.product))
            i2 += 1
        }
        
        val recommendations3 = bestModel.get
            .predict(candidates.map((3, _)))
            .collect()
            .sortBy(-_.rating)
            .take(10)
        var i3 = 1
        recommendations3.foreach { r =>
            println(r.user + ":" + r.product + ":" + r.rating + ":" + movies(r.product))
            i3 += 1
        }
        
        val recommendations4 = bestModel.get
            .predict(candidates.map((4, _)))
            .collect()
            .sortBy(-_.rating)
            .take(10)
        var i4 = 1
        recommendations4.foreach { r =>
            println(r.user + ":" + r.product + ":" + r.rating + ":" + movies(r.product))
            i4 += 1
        }
        
        sc.stop()
    }
    
    /** 校验集预测数据和实际数据之间的均方根误差 * */
    //输入训练模型、校验样本、校验个数
    def computeRmse(model: MatrixFactorizationModel, data: RDD[Rating], n: Long): Double = {
        val predictions = model.predict(data.map(x => (x.user, x.product))) //调用预测的函数
        // 输出predictionsAndRatings预测和评分
        val predictionsAndRatings = predictions.map(x => ((x.user, x.product), x.rating))
            .join(data.map(f = x => ((x.user, x.product), x.rating)))
            .values
        math.sqrt(predictionsAndRatings.map(x => (x._1 - x._2) * (x._1 - x._2)).reduce(_ + _) / n)
    }
    
    /** 装载用户评分文件 * */
    def loadRatings(path: String): Seq[Rating] = {
        val lines = Source.fromFile(path).getLines()
        val ratings = lines.map { line =>
            val fields = line.split("::")
            Rating(fields(0).toInt, fields(1).toInt, fields(2).toDouble)
        }.filter(_.rating > 0.0)
        if (ratings.isEmpty) {
            sys.error("No ratings provided.")
        } else {
            ratings.toSeq
        }
    }
}
