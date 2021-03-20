
package org.movie.controller

import java.util.Date

import com.alibaba.fastjson.JSONObject
import org.movie.recommendation.MovieDataAnalysis
import org.springframework.web.bind.annotation.{CrossOrigin, RequestMapping, RequestParam, RestController}
@RestController
@CrossOrigin
class DataController {
    @RequestMapping(Array("/hello"))
    def greeting(@RequestParam(value="name", defaultValue="LightSword")  name: String) = {
        val now = new Date
        val content =  "Hello, " + name + "! Now is: " + now
        val json = new JSONObject
        json.put("conent", content)
        json
    }
    @RequestMapping(Array("/hi"))
    def hiTest() ={
        "hi"
    }
    @RequestMapping(Array("/getTolalMovieCountByYear"))
    def getTolalMovieCountByYear() ={
        MovieDataAnalysis.totalMovieByYear()
    }
    @RequestMapping(Array("/getTotalMovieRatingRiscretization"))
    def getTotalMovieRatingRiscretization() ={
        MovieDataAnalysis.totalMovieRatingRiscretization()
    }
    @RequestMapping(Array("/getTotalAverageMovieRating"))
    def getTotalAverageMovieRating() ={
        MovieDataAnalysis.totalAverageMovieRating()
    }
    @RequestMapping(Array("/getTotalMovieTypeNum"))
    def getTotalMovieTypeNum() ={
        MovieDataAnalysis.totalMovieTypeNum()
    }
    
    
}
