package io.github.rayvader.kirocrew.core

/** Whether the app can reach a saved gateway: the status dot's colour. */
enum class Connection {
    /** Not asked yet. Grey dot. */
    UNKNOWN,

    /** The gateway answered. Green dot. */
    UP,

    /** No answer: network error, timeout or no route. Red dot. */
    DOWN;

    companion object {
        /** Public, no login needed, cheap on the gateway. */
        const val HEALTH_PATH = "/api/health"

        fun healthUrl(baseUrl: String): String = UrlTools.buildUrl(baseUrl, HEALTH_PATH)

        /**
         * Any HTTP answer means the gateway is up, even 401 or 500: the dot says
         * "reachable", and a login problem has its own notice. Only no answer at
         * all (null) is DOWN.
         */
        fun fromHttpCode(code: Int?): Connection = if (code != null && code in 100..599) UP else DOWN
    }
}
