package br.com.jrnischjr.brasileiraopredictor

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DadosBrasileirao {

    private const val BASE_URL =
        "https://cold-sbrasileirao-predictor-apiilence-07ef.jrnischjr.workers.dev/"

    fun buscarJogos(): JSONObject {
        val url = URL("${BASE_URL}?endpoint=fd-matches")
        val conexao = url.openConnection() as HttpURLConnection

        try {
            conexao.requestMethod = "GET"
            conexao.connectTimeout = 15000
            conexao.readTimeout = 15000

            val resposta = conexao.inputStream
                .bufferedReader()
                .use { it.readText() }

            return JSONObject(resposta)

        } finally {
            conexao.disconnect()
        }
    }
}
