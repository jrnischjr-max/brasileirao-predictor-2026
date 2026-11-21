package br.com.jrnischjr.brasileiraopredictor

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object DadosBrasileirao {

    private const val BASE_URL =
        "https://cold-sbrasileirao-predictor-apiilence-07ef.jrnischjr.workers.dev/"

    // Cache local por endpoint. Dados antigos são exibidos imediatamente;
    // a rede atualiza o arquivo e notifica a tela novamente quando terminar.
    private const val CACHE_JOGOS = "cache_jogos_2026.json"
    private const val CACHE_CLASSIFICACAO = "cache_classificacao_2026.json"
    private const val CACHE_HISTORICO = "cache_historico_2025.json"

    private fun arquivoCache(context: Context, endpoint: String): File? {
        val nome = when (endpoint) {
            "fd-matches" -> CACHE_JOGOS
            "fd-standings" -> CACHE_CLASSIFICACAO
            "fd-history" -> CACHE_HISTORICO
            else -> return null
        }
        return File(context.applicationContext.cacheDir, nome)
    }

    private fun lerCache(context: Context?, endpoint: String): JSONObject? {
        if (context == null) return null
        return try {
            val arquivo = arquivoCache(context, endpoint) ?: return null
            if (!arquivo.exists() || arquivo.length() == 0L) return null
            JSONObject(arquivo.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun gravarCache(context: Context?, endpoint: String, dados: JSONObject) {
        if (context == null) return
        try {
            val arquivo = arquivoCache(context, endpoint) ?: return
            val temporario = File(arquivo.parentFile, "${arquivo.name}.tmp")
            temporario.writeText(dados.toString(), Charsets.UTF_8)
            if (!temporario.renameTo(arquivo)) {
                arquivo.writeText(dados.toString(), Charsets.UTF_8)
                temporario.delete()
            }
        } catch (_: Exception) {
            // Falha de cache nunca pode impedir o uso dos dados da rede.
        }
    }

    private fun buscarDados(
        endpoint: String,
        callback: (JSONObject?, String?) -> Unit,
        context: Context? = null,
        timeoutMs: Int = 15000
    ) {
        val appContext = context?.applicationContext
        val cache = appContext?.let { lerCache(it, endpoint) }

        // Mostra o conteúdo salvo antes de iniciar a atualização remota.
        if (cache != null) {
            Handler(Looper.getMainLooper()).post {
                callback(cache, null)
            }
        }

        Thread {
            var conexao: HttpURLConnection? = null
            try {
                val url = URL("${BASE_URL}?endpoint=$endpoint")
                conexao = url.openConnection() as HttpURLConnection
                conexao.requestMethod = "GET"
                conexao.connectTimeout = timeoutMs
                conexao.readTimeout = timeoutMs

                val codigo = conexao.responseCode
                val fluxo = if (codigo in 200..299) conexao.inputStream else conexao.errorStream
                val resposta = fluxo?.bufferedReader()?.use { it.readText() }
                    ?: throw Exception("Resposta vazia do servidor.")

                if (codigo !in 200..299) {
                    throw Exception("Erro do servidor: HTTP $codigo")
                }

                val dados = JSONObject(resposta)
                gravarCache(appContext, endpoint, dados)

                Handler(Looper.getMainLooper()).post {
                    callback(dados, null)
                }
            } catch (erro: Exception) {
                // Se já exibimos o cache, não substituímos a tela por um erro de rede.
                if (cache == null) {
                    Handler(Looper.getMainLooper()).post {
                        callback(null, erro.message ?: "Falha ao buscar os dados.")
                    }
                }
            } finally {
                conexao?.disconnect()
            }
        }.start()
    }

    fun buscarJogos(
        callback: (JSONObject?, String?) -> Unit
    ) = buscarJogos(null, callback)

    fun buscarJogos(
        context: Context?,
        callback: (JSONObject?, String?) -> Unit
    ) {
        buscarDados("fd-matches", callback, context)
    }

    fun buscarClassificacao(
        callback: (JSONObject?, String?) -> Unit
    ) = buscarClassificacao(null, callback)

    fun buscarClassificacao(
        context: Context?,
        callback: (JSONObject?, String?) -> Unit
    ) {
        buscarDados("fd-standings", callback, context)
    }

    fun buscarHistorico(
        callback: (JSONObject?, String?) -> Unit
    ) = buscarHistorico(null, callback)

    fun buscarHistorico(
        context: Context?,
        callback: (JSONObject?, String?) -> Unit
    ) {
        buscarDados("fd-history", callback, context, timeoutMs = 90000)
    }
}
