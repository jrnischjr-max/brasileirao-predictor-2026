package br.com.jrnischjr.brasileiraopredictor

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.json.JSONObject

object HistoricoPalpitesNuvem {

    private val db = FirebaseFirestore.getInstance()

    private fun colecao(uid: String) =
        db.collection("users")
            .document(uid)
            .collection("historicoPalpites")

    fun carregar(
        uid: String,
        callback: (List<JSONObject>?, String?) -> Unit
    ) {
        colecao(uid)
            .get()
            .addOnSuccessListener { documentos ->
                val lista = documentos.documents.mapNotNull { documento ->
                    documento.data?.let { JSONObject(it) }
                }
                callback(lista, null)
            }
            .addOnFailureListener { erro ->
                callback(null, erro.message ?: "Erro ao carregar histórico.")
            }
    }

    fun salvar(
        uid: String,
        jogo: JSONObject,
        analise: AnalisePalpite,
        callback: (Boolean, String?) -> Unit
    ) {
        val mandante = jogo.optJSONObject("mandante")
            ?.optString("nome", "") ?: ""

        val visitante = jogo.optJSONObject("visitante")
            ?.optString("nome", "") ?: ""

        val idJogo = jogo.optString("id").ifBlank {
            "${jogo.optString("rodada")}_${jogo.optString("data")}_${mandante}_${visitante}"
        }

        val documento = colecao(uid).document(idJogo)

        documento.get()
            .addOnSuccessListener { existente ->

                if (existente.getBoolean("finalizado") == true) {
                    callback(true, null)
                    return@addOnSuccessListener
                }

                val resultadoPrevisto = when {
                    analise.placarCasa > analise.placarFora -> "CASA"
                    analise.placarCasa < analise.placarFora -> "FORA"
                    else -> "EMPATE"
                }

                val registro = hashMapOf<String, Any>(
                    "idJogo" to idJogo,
                    "rodada" to jogo.optString("rodada"),
                    "data" to jogo.optString("data"),
                    "mandante" to mandante,
                    "visitante" to visitante,
                    "probCasa" to analise.probCasa,
                    "probEmpate" to analise.probEmpate,
                    "probFora" to analise.probFora,
                    "placarCasa" to analise.placarCasa,
                    "placarFora" to analise.placarFora,
                    "resultadoPrevisto" to resultadoPrevisto,
                    "finalizado" to false
                )

                documento.set(registro, SetOptions.merge())
                    .addOnSuccessListener {
                        callback(true, null)
                    }
                    .addOnFailureListener { erro ->
                        callback(false, erro.message ?: "Erro ao salvar palpite.")
                    }
            }
            .addOnFailureListener { erro ->
                callback(false, erro.message ?: "Erro ao verificar palpite.")
            }
    }

    fun gerarSimulacaoHistorica(
        uid: String,
        jogos: List<JSONObject>,
        callback: (Int, String?) -> Unit
    ) {
        colecao(uid).get()
            .addOnSuccessListener { existentes ->
                val idsExistentes = existentes.documents.map { it.id }.toSet()
                val lote = db.batch()
                var quantidade = 0

                jogos.forEach { jogo ->
                    val situacao = jogo.optString("situacao").uppercase()
                    if (situacao !in listOf("FINISHED", "FINALIZADO", "AWARDED")) return@forEach

                    val casa = jogo.optJSONObject("mandante")?.optString("nome", "").orEmpty()
                    val fora = jogo.optJSONObject("visitante")?.optString("nome", "").orEmpty()
                    val id = jogo.optString("id").ifBlank {
                        "${jogo.optString("rodada")}_${jogo.optString("data")}_${casa}_${fora}"
                    }
                    if (id in idsExistentes || casa.isBlank() || fora.isBlank()) return@forEach

                    val placar = jogo.optJSONObject("placar") ?: return@forEach
                    val golsCasa = placar.optInt("mandante", -1)
                    val golsFora = placar.optInt("visitante", -1)
                    if (golsCasa < 0 || golsFora < 0) return@forEach

                    val analise = MotorPalpites.analisarHistorico(jogo, jogos) ?: return@forEach
                    val previsto = when {
                        analise.placarCasa > analise.placarFora -> "CASA"
                        analise.placarCasa < analise.placarFora -> "FORA"
                        else -> "EMPATE"
                    }
                    val real = when {
                        golsCasa > golsFora -> "CASA"
                        golsCasa < golsFora -> "FORA"
                        else -> "EMPATE"
                    }

                    val registro = hashMapOf<String, Any>(
                        "idJogo" to id,
                        "rodada" to jogo.optString("rodada"),
                        "data" to jogo.optString("data"),
                        "mandante" to casa,
                        "visitante" to fora,
                        "probCasa" to analise.probCasa,
                        "probEmpate" to analise.probEmpate,
                        "probFora" to analise.probFora,
                        "placarCasa" to analise.placarCasa,
                        "placarFora" to analise.placarFora,
                        "resultadoPrevisto" to previsto,
                        "golsReaisCasa" to golsCasa,
                        "golsReaisFora" to golsFora,
                        "resultadoReal" to real,
                        "acertouResultado" to (previsto == real),
                        "acertouPlacar" to (analise.placarCasa == golsCasa && analise.placarFora == golsFora),
                        "finalizado" to true,
                        "origem" to "SIMULACAO_HISTORICA"
                    )
                    lote.set(colecao(uid).document(id), registro)
                    quantidade++
                }

                if (quantidade == 0) {
                    callback(0, null)
                } else {
                    lote.commit()
                        .addOnSuccessListener { callback(quantidade, null) }
                        .addOnFailureListener { erro ->
                            callback(0, erro.message ?: "Erro ao salvar simulação histórica.")
                        }
                }
            }
            .addOnFailureListener { erro ->
                callback(0, erro.message ?: "Erro ao consultar histórico existente.")
            }
    }

    fun atualizarResultados(
        uid: String,
        jogos: List<JSONObject>,
        callback: (List<JSONObject>?, String?) -> Unit
    ) {
        colecao(uid)
            .get()
            .addOnSuccessListener { documentos ->

                val atualizados = mutableListOf<JSONObject>()
                val lote = db.batch()
                var houveAtualizacao = false

                documentos.documents.forEach { documento ->

                    val dados = documento.data ?: return@forEach
                    val palpite = JSONObject(dados)
                    val jaEstavaFinalizado = palpite.optBoolean("finalizado")

                    if (!jaEstavaFinalizado) {

                        val id = palpite.optString("idJogo")

                        val jogoReal = jogos.firstOrNull { jogo ->
                            val idReal = jogo.optString("id")

                            if (idReal.isNotBlank()) {
                                idReal == id
                            } else {
                                val casa = jogo.optJSONObject("mandante")
                                    ?.optString("nome", "") ?: ""

                                val fora = jogo.optJSONObject("visitante")
                                    ?.optString("nome", "") ?: ""

                                id == "${jogo.optString("rodada")}_${jogo.optString("data")}_${casa}_${fora}"
                            }
                        }

                        if (jogoReal != null) {
                            val situacao = jogoReal
                                .optString("situacao")
                                .uppercase()

                            if (situacao in listOf(
                                    "FINISHED",
                                    "FINALIZADO",
                                    "AWARDED"
                                )
                            ) {
                                val placar = jogoReal.optJSONObject("placar")

                                val golsCasa = placar?.optInt("mandante", -1) ?: -1
                                val golsFora = placar?.optInt("visitante", -1) ?: -1

                                if (golsCasa >= 0 && golsFora >= 0) {
                                    palpite.put("golsReaisCasa", golsCasa)
                                    palpite.put("golsReaisFora", golsFora)
                                    palpite.put("finalizado", true)
                                }
                            }
                        }
                    }

                    if (palpite.optBoolean("finalizado")) {
                        // Reavalia também partidas antigas para preencher/corrigir
                        // as métricas de resultado e placar exato no histórico.
                        val resultadoAnterior = palpite.optString("resultadoReal")
                        val acertouResultadoAnterior = palpite.optBoolean("acertouResultado")
                        val acertouPlacarAnterior = palpite.optBoolean("acertouPlacar")
                        val tinhaMetricas = palpite.has("resultadoReal") &&
                            palpite.has("acertouResultado") &&
                            palpite.has("acertouPlacar")

                        avaliar(palpite)

                        val precisaSalvar = !jaEstavaFinalizado ||
                            !tinhaMetricas ||
                            resultadoAnterior != palpite.optString("resultadoReal") ||
                            acertouResultadoAnterior != palpite.optBoolean("acertouResultado") ||
                            acertouPlacarAnterior != palpite.optBoolean("acertouPlacar")

                        if (precisaSalvar) {
                            lote.set(
                                documento.reference,
                                palpite.toMap(),
                                SetOptions.merge()
                            )
                            houveAtualizacao = true
                        }
                    }

                    atualizados.add(palpite)
                }

                if (!houveAtualizacao) {
                    callback(atualizados, null)
                } else {
                    lote.commit()
                        .addOnSuccessListener {
                            callback(atualizados, null)
                        }
                        .addOnFailureListener { erro ->
                            callback(null, erro.message ?: "Erro ao atualizar histórico.")
                        }
                }
            }
            .addOnFailureListener { erro ->
                callback(null, erro.message ?: "Erro ao consultar histórico.")
            }
    }

    private fun avaliar(palpite: JSONObject) {
        val golsCasa = palpite.optInt("golsReaisCasa", -1)
        val golsFora = palpite.optInt("golsReaisFora", -1)
        val previstoCasa = palpite.optInt("placarCasa", -1)
        val previstoFora = palpite.optInt("placarFora", -1)

        if (
            golsCasa < 0 || golsFora < 0 ||
            previstoCasa < 0 || previstoFora < 0
        ) return

        val resultadoReal = when {
            golsCasa > golsFora -> "CASA"
            golsCasa < golsFora -> "FORA"
            else -> "EMPATE"
        }

        val resultadoPrevisto = when {
            previstoCasa > previstoFora -> "CASA"
            previstoCasa < previstoFora -> "FORA"
            else -> "EMPATE"
        }

        palpite.put("resultadoPrevisto", resultadoPrevisto)
        palpite.put("resultadoReal", resultadoReal)
        palpite.put("acertouResultado", resultadoPrevisto == resultadoReal)
        palpite.put(
            "acertouPlacar",
            previstoCasa == golsCasa && previstoFora == golsFora
        )
    }

    private fun JSONObject.toMap(): Map<String, Any> {
        val mapa = mutableMapOf<String, Any>()
        val chaves = keys()

        while (chaves.hasNext()) {
            val chave = chaves.next()
            val valor = opt(chave)

            if (valor != null && valor != JSONObject.NULL) {
                mapa[chave] = valor
            }
        }

        return mapa
    }
}
