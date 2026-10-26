package com.arslan.resqlink

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform