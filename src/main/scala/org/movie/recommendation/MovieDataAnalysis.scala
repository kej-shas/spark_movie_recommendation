package org.movie.recommendation

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{SQLContext, SparkSession}
import org.apache.spark.{SparkConf, SparkContext}

object MovieDataAnalysis {
    val conf = new SparkConf().setAppName("MovieDataAnalysis").setMaster("local[*]")
    val sc = new SparkContext(conf)
    val spark:SparkSession=SparkSession.builder()
        .master("local[*]")
        .config("spark.some.config.option", "some-value")
        .appName("sparlksql")
        .getOrCreate()
    import spark.implicits._
    init("data/movies.dat","data/ratings.dat")
    
    def init(movie_path:String,rating_path:String): Unit ={
        val movie: RDD[MovieModel] = loadMovieData(movie_path)
        val rating=loadRatingsData(rating_path)
        val df_movie = movie.toDF
        df_movie.createOrReplaceTempView("movies")
        val  df_rating=rating.toDF
        df_rating.createOrReplaceTempView("ratings")
    }
    
    def loadMovieData(path: String): RDD[MovieModel] = {
        val movies = sc.textFile(path).map {
            line =>
            val fields = line.split("::")
            
            MovieModel(fields(0).toInt,
                "([A-Za-z ,']+)".r.findAllIn(fields(1).toString()).toList.apply(0).toString.replace(", The",""),
                "(?<=\\()([0-9]+)(?=\\))".r.findAllIn(fields(1).toString()).toList.apply(0).toInt,
                fields(2).split("\\|").apply(0)
            )
    
    
        }
        //movies.repartition(1).saveAsTextFile("data/out/data_move_split.dat")
        movies
        
    }
    def loadRatingsData(path:String)={
        var rating=sc.textFile(path)map{
            line=>
                var fields=line.split("::")
                var grade=
                    if (fields(2).toInt < 2) "差"
                    else if (fields(2).toInt < 4) "良好"
                    else "优秀"
                RatingModel(
                    fields(0).toInt,
                    fields(1).toInt,
                    fields(2).toInt,
                    grade
                )
        }
        rating
    }
    def totalMovieByYear() = {
        val year = spark.sql(
            "select year,count(year) as num from movies group by year order by year"
        )
        year.toJSON.collectAsList.toString
    }
    def totalMovieRatingRiscretization()={
        var rating_riscretization=spark.sql(
            "select grade,count(movieid) from ratings group by grade order by grade desc "
        )
        rating_riscretization.toJSON.collectAsList.toString
    }
    def totalAverageMovieRating()={
        val average_movie_rating=spark.sql(
            "select name,ROUND(avg(score),2) as avgscore " +
                "from movies a,ratings b " +
                "where a.id=b.movieid "+
                "group by name " +
                "order by avgscore desc"
        )
        average_movie_rating.toJSON.collectAsList.toString
    }
    def totalMovieTypeNum()={
        var movie_type_num=spark.sql(
            "select genre,count(id) as num " +
                "from movies group by genre"
        )
        movie_type_num.toJSON.collectAsList.toString
    }
    
}
