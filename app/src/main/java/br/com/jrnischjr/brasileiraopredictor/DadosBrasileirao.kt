package br.com.jrnischjr.brasileiraopredictor

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DadosBrasileirao {

    private const val BASE_URL =
        "https://cold-sbrasileirao-predictor-apiilence-07ef.jrnischjr.workers.dev/"

    fun buscarJogos(
        callback: (JSONObject?, String?) -> Unit
    ) {
        Thread {
            var conexao: HttpURLConnection? = null

            try {
                val url = URL("${BASE_URL}?endpoint=fd-matches")
                conexao = url.openConnection() as HttpURLConnection

                conexao.requestMethod = "GET"
                conexao.connectTimeout = 15000
                conexao.readTimeout = 15000

                val codigo = conexao.responseCode

                val fluxo = if (codigo in 200..299) {
                    conexao.inputStream
                } else {
                    conexao.errorStream
                }

                val resposta = fluxo
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: throw Exception("Resposta vazia do servidor.")

                if (codigo !in 200..299) {
                    throw Exception("Erro do servidor: HTTP $codigo")
                }

                val dados = JSONObject(resposta)

                Handler(Looper.getMainLooper()).post {
                    callback(dados, null)
                }

            } catch (erro: Exception) {

                Handler(Looper.getMainLooper()).post {
                    callback(
                        null,
                        erro.message ?: "Falha ao buscar os jogos."
                    )
                }

            } finally {
                conexao?.disconnect()
            }
        }.start()
    }
}
