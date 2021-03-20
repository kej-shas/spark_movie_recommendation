package org.movie.controller

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping

@Controller
class MainController {
    @RequestMapping(Array("/index","/"))
    def index()={
        "index"
    }
}
