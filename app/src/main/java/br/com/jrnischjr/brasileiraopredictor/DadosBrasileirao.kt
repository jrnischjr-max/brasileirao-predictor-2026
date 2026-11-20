
package br.com.jrnischjr.brasileiraopredictor

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DadosBrasileirao {

    private const val BASE_URL =
        "https://cold-sbrasileirao-predictor-apiilence-07ef.jrnischjr.workers.dev/"

    private fun buscarDados(
        endpoint: String,
        callback: (JSONObject?, String?) -> Unit,
        timeoutMs: Int = 15000
    ) {
        Thread {
            var conexao: HttpURLConnection? = null

            try {
                val url = URL("${BASE_URL}?endpoint=$endpoint")
                conexao = url.openConnection() as HttpURLConnection

                conexao.requestMethod = "GET"
                conexao.connectTimeout = timeoutMs
                conexao.readTimeout = timeoutMs

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
                        erro.message ?: "Falha ao buscar os dados."
                    )
                }

            } finally {
                conexao?.disconnect()
            }
        }.start()
    }

    fun buscarJogos(
        callback: (JSONObject?, String?) -> Unit
    ) {
        buscarDados("fd-matches", callback)
    }

    fun buscarClassificacao(
        callback: (JSONObject?, String?) -> Unit
    ) {
        buscarDados("fd-standings", callback)
    }

    // Carrega as temporadas históricas disponíveis no Worker.
    // A consulta pode levar mais tempo porque o Worker consulta várias temporadas.
    fun buscarHistorico(
        callback: (JSONObject?, String?) -> Unit
    ) {
        buscarDados("fd-history", callback = callback, timeoutMs = 90000)
    }
}
