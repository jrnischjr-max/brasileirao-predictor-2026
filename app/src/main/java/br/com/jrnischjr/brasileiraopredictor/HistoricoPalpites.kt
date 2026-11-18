package br.com.jrnischjr.brasileiraopredictor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object HistoricoPalpites {

    private const val PREFERENCIAS = "historico_palpites"
    private const val CHAVE = "palpites_salvos"

    private fun preferencias(context: Context) =
        context.getSharedPreferences(
            PREFERENCIAS,
            Context.MODE_PRIVATE
        )

    fun carregar(context: Context): List<JSONObject> {
        return try {
            val texto = preferencias(context)
                .getString(CHAVE, "[]") ?: "[]"

            val lista = JSONArray(texto)

            (0 until lista.length()).mapNotNull {
                lista.optJSONObject(it)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun salvar(
        context: Context,
        jogo: JSONObject,
        analise: AnalisePalpite
    ) {
        val mandante = jogo.optJSONObject("mandante")
            ?.optString("nome", "") ?: ""

        val visitante = jogo.optJSONObject("visitante")
            ?.optString("nome", "") ?: ""

        val idJogo = jogo.optString("id").ifBlank {
            "${jogo.optString("rodada")}_${jogo.optString("data")}_${mandante}_${visitante}"
        }

        val palpites = carregar(context).toMutableList()

        val registro = JSONObject().apply {
            put("idJogo", idJogo)
            put("rodada", jogo.optString("rodada"))
            put("data", jogo.optString("data"))
            put("mandante", mandante)
            put("visitante", visitante)

            put("probCasa", analise.probCasa)
            put("probEmpate", analise.probEmpate)
            put("probFora", analise.probFora)

            put("placarCasa", analise.placarCasa)
            put("placarFora", analise.placarFora)

            put(
                "resultadoPrevisto",
                when {
                    analise.probCasa >= analise.probEmpate &&
                    analise.probCasa >= analise.probFora -> "CASA"

                    analise.probFora >= analise.probEmpate -> "FORA"

                    else -> "EMPATE"
                }
            )

            put("finalizado", false)
        }

        val indice = palpites.indexOfFirst {
            it.optString("idJogo") == idJogo
        }

        if (indice >= 0) {
            if (palpites[indice].optBoolean("finalizado")) {
                return
            }
            palpites[indice] = registro
        } else {
            palpites.add(registro)
        }

        gravar(context, palpites)
    }

    fun atualizarResultados(
        context: Context,
        jogos: List<JSONObject>
    ): List<JSONObject> {

        val palpites = carregar(context).toMutableList()

        palpites.forEach { palpite ->
            if (palpite.optBoolean("finalizado")) {
                return@forEach
            }

            val id = palpite.optString("idJogo")

            val jogoReal = jogos.firstOrNull {
                val idReal = it.optString("id")

                if (idReal.isNotBlank()) {
                    idReal == id
                } else {
                    val casa = it.optJSONObject("mandante")
                        ?.optString("nome", "") ?: ""

                    val fora = it.optJSONObject("visitante")
                        ?.optString("nome", "") ?: ""

                    id == "${it.optString("rodada")}_${it.optString("data")}_${casa}_${fora}"
                }
            } ?: return@forEach

            val situacao = jogoReal
                .optString("situacao")
                .uppercase()

            if (situacao !in listOf(
                    "FINISHED",
                    "FINALIZADO",
                    "AWARDED"
                )
            ) {
                return@forEach
            }

            val placar = jogoReal.optJSONObject("placar")
                ?: return@forEach

            val golsCasa = placar.optInt("mandante", -1)
            val golsFora = placar.optInt("visitante", -1)

            if (golsCasa < 0 || golsFora < 0) {
                return@forEach
            }

            val resultadoReal = when {
                golsCasa > golsFora -> "CASA"
                golsCasa < golsFora -> "FORA"
                else -> "EMPATE"
            }

            val placarPrevistoCasa =
                palpite.optInt("placarCasa")

            val placarPrevistoFora =
                palpite.optInt("placarFora")

            palpite.put("golsReaisCasa", golsCasa)
            palpite.put("golsReaisFora", golsFora)
            palpite.put("resultadoReal", resultadoReal)

            palpite.put(
                "acertouResultado",
                palpite.optString("resultadoPrevisto") == resultadoReal
            )

            palpite.put(
                "acertouPlacar",
                placarPrevistoCasa == golsCasa &&
                placarPrevistoFora == golsFora
            )

            palpite.put("finalizado", true)
        }

        gravar(context, palpites)

        return palpites
    }

    private fun gravar(
        context: Context,
        palpites: List<JSONObject>
    ) {
        val lista = JSONArray()

        palpites.forEach {
            lista.put(it)
        }

        preferencias(context)
            .edit()
            .putString(CHAVE, lista.toString())
            .apply()
    }
}

