package br.com.jrnischjr.brasileiraopredictor

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.DocumentSnapshot
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import android.content.Context
import android.os.Handler
import android.os.Looper

object HistoricoPalpitesNuvem {

    private val db = FirebaseFirestore.getInstance()
    private val simulacaoExecutor = Executors.newSingleThreadExecutor()
    // Conversão de documentos grandes fora da thread da interface.
    private val leituraExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun resultadoMaisProvavel(analise: AnalisePalpite): String {
        // A pontuação precisa corresponder ao placar que aparece para o usuário.
        return when {
            analise.placarCasa > analise.placarFora -> "CASA"
            analise.placarCasa < analise.placarFora -> "FORA"
            else -> "EMPATE"
        }
    }

    private fun colecao(uid: String) =
        db.collection("users")
            .document(uid)
            .collection("historicoPalpites")

    private fun arquivoCache(context: Context, uid: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(uid.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(context.applicationContext.filesDir, "historico_usuario_$hash.json")
    }

    private fun lerCache(context: Context, uid: String): List<JSONObject>? {
        return try {
            val arquivo = arquivoCache(context, uid)
            if (!arquivo.exists() || arquivo.length() == 0L) return null
            val array = JSONArray(arquivo.readText(Charsets.UTF_8))
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun gravarCache(context: Context, uid: String, lista: List<JSONObject>) {
        try {
            val arquivo = arquivoCache(context, uid)
            val temporario = File(arquivo.parentFile, "${arquivo.name}.tmp")
            val array = JSONArray()
            lista.forEach { array.put(it) }
            temporario.writeText(array.toString(), Charsets.UTF_8)
            if (!temporario.renameTo(arquivo)) {
                arquivo.writeText(array.toString(), Charsets.UTF_8)
                temporario.delete()
            }
        } catch (_: Exception) {
            // O cache é auxiliar: falhas locais não podem bloquear o Firebase.
        }
    }

    fun carregar(
        uid: String,
        context: Context,
        callback: (List<JSONObject>?, String?) -> Unit
    ) {
        val cache = lerCache(context, uid)
        if (cache != null) {
            mainHandler.post { callback(cache, null) }
        }
        carregar(uid) { lista, erro ->
            if (lista != null) gravarCache(context, uid, lista)
            if (lista != null || cache == null) callback(lista, erro)
        }
    }

    fun carregar(
        uid: String,
        callback: (List<JSONObject>?, String?) -> Unit
    ) {
        // Busca em páginas pequenas para evitar baixar milhares de documentos
        // em uma única operação e pressionar memória/UI em aparelhos modestos.
        val listaCompleta = mutableListOf<JSONObject>()
        var ultimoDocumento: DocumentSnapshot? = null

        fun buscarProximaPagina() {
            var consulta = colecao(uid)
                .orderBy(FieldPath.documentId())
                .limit(200)

            ultimoDocumento?.let { consulta = consulta.startAfter(it) }

            consulta.get()
                .addOnSuccessListener { pagina ->
                    if (pagina.isEmpty) {
                        callback(listaCompleta, null)
                        return@addOnSuccessListener
                    }

                    ultimoDocumento = pagina.documents.last()
                    leituraExecutor.execute {
                        val convertidos = pagina.documents.mapNotNull { documento ->
                            try {
                                documento.data?.let { JSONObject(it) }
                            } catch (_: Exception) {
                                null
                            }
                        }
                        listaCompleta.addAll(convertidos)
                        mainHandler.post {
                            if (pagina.size() < 200) {
                                callback(listaCompleta, null)
                            } else {
                                buscarProximaPagina()
                            }
                        }
                    }
                }
                .addOnFailureListener { erro ->
                    callback(null, erro.message ?: "Erro ao carregar histórico.")
                }
        }

        buscarProximaPagina()
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

                val resultadoPrevisto = resultadoMaisProvavel(analise)

                val registro = hashMapOf<String, Any>(
                    "idJogo" to idJogo,
                    "rodada" to jogo.optString("rodada"),
                    "data" to jogo.optString("data"),
                    "mandante" to mandante,
                    "visitante" to visitante,
                    "probCasa" to analise.probCasa,
                    "probEmpate" to analise.probEmpate,
                    "probFora" to analise.probFora,
                    "probCasaExata" to analise.probCasaExata,
                    "probEmpateExata" to analise.probEmpateExata,
                    "probForaExata" to analise.probForaExata,
                    "golsEsperadosCasa" to analise.golsEsperadosCasa,
                    "golsEsperadosFora" to analise.golsEsperadosFora,
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

    fun salvarPalpiteUsuario(
        uid: String,
        jogo: JSONObject,
        golsCasa: Int,
        golsFora: Int,
        callback: (Boolean, String?) -> Unit
    ) {
        if (uid.isBlank()) {
            callback(false, "Faça login para salvar seu palpite.")
            return
        }
        if (golsCasa !in 0..20 || golsFora !in 0..20) {
            callback(false, "Informe gols entre 0 e 20 para cada time.")
            return
        }

        val dataJogo = jogo.optString("data")
        val inicio = try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }.parse(dataJogo.take(19))?.time
        } catch (_: Exception) {
            null
        }
        if (inicio == null) {
            callback(false, "Não foi possível confirmar o horário da partida.")
            return
        }
        if (System.currentTimeMillis() >= inicio) {
            callback(false, "Palpites bloqueados: a partida já começou.")
            return
        }

        val mandante = jogo.optJSONObject("mandante")?.optString("nome", "").orEmpty()
        val visitante = jogo.optJSONObject("visitante")?.optString("nome", "").orEmpty()
        val idJogo = jogo.optString("id").ifBlank {
            "${jogo.optString("rodada")}_${dataJogo}_${mandante}_${visitante}"
        }
        val documento = colecao(uid).document(idJogo)
        documento.get()
            .addOnSuccessListener { existente ->
                if (existente.getBoolean("finalizado") == true ||
                    System.currentTimeMillis() >= inicio
                ) {
                    callback(false, "Palpites bloqueados: a partida já começou ou foi finalizada.")
                    return@addOnSuccessListener
                }
                val registro = hashMapOf<String, Any>(
                    "idJogo" to idJogo,
                    "rodada" to jogo.optString("rodada"),
                    "data" to dataJogo,
                    "mandante" to mandante,
                    "visitante" to visitante,
                    "usuarioPlacarCasa" to golsCasa,
                    "usuarioPlacarFora" to golsFora,
                    "usuarioResultadoPrevisto" to when {
                        golsCasa > golsFora -> "CASA"
                        golsCasa < golsFora -> "FORA"
                        else -> "EMPATE"
                    },
                    "usuarioAtualizadoEm" to System.currentTimeMillis(),
                    "finalizado" to false,
                    "origem" to (existente.getString("origem") ?: "PALPITE_USUARIO")
                )
                documento.set(registro, SetOptions.merge())
                    .addOnSuccessListener { callback(true, null) }
                    .addOnFailureListener { erro ->
                        callback(false, erro.message ?: "Erro ao salvar seu palpite.")
                    }
            }
            .addOnFailureListener { erro ->
                callback(false, erro.message ?: "Erro ao verificar a partida.")
            }
    }

    fun gerarSimulacaoHistorica(
        uid: String,
        jogos: List<JSONObject>,
        callback: (Int, String?) -> Unit
    ) {
        colecao(uid).get()
            .addOnSuccessListener { existentes ->
                // A reconstrução de mais de mil partidas é pesada. Nunca executar
                // esse processamento na thread principal, para evitar ANR/fechamento.
                simulacaoExecutor.execute {
                val documentosExistentes = existentes.documents.associateBy { it.id }
                val operacoes = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any>>>()

                jogos.forEach { jogo ->
                    val situacao = jogo.optString("situacao").uppercase()
                    if (situacao !in listOf("FINISHED", "FINALIZADO", "AWARDED")) return@forEach

                    val casa = jogo.optJSONObject("mandante")?.optString("nome", "").orEmpty()
                    val fora = jogo.optJSONObject("visitante")?.optString("nome", "").orEmpty()
                    val id = jogo.optString("id").ifBlank {
                        "${jogo.optString("rodada")}_${jogo.optString("data")}_${casa}_${fora}"
                    }
                    val documentoExistente = documentosExistentes[id]

                    // Nunca sobrescreve palpites reais do motor ou do usuário.
                    if ((documentoExistente != null &&
                            documentoExistente.getString("origem") != "SIMULACAO_HISTORICA") ||
                        casa.isBlank() || fora.isBlank()
                    ) return@forEach

                    val placar = jogo.optJSONObject("placar") ?: return@forEach
                    val golsCasa = placar.optInt("mandante", -1)
                    val golsFora = placar.optInt("visitante", -1)
                    if (golsCasa < 0 || golsFora < 0) return@forEach

                    val analise = MotorPalpites.analisarHistorico(jogo, jogos) ?: return@forEach
                    val previsto = resultadoMaisProvavel(analise)
                    val elo = MotorPalpites.probabilidadesEloHistorico(jogo, jogos)
                    val eloCasa = elo?.casa ?: analise.probCasaExata
                    val eloEmpate = elo?.empate ?: analise.probEmpateExata
                    val eloFora = elo?.fora ?: analise.probForaExata
                    val combinadoCasa = (analise.probCasaExata + eloCasa) / 2.0
                    val combinadoEmpate = (analise.probEmpateExata + eloEmpate) / 2.0
                    val combinadoFora = (analise.probForaExata + eloFora) / 2.0
                    val real = when {
                        golsCasa > golsFora -> "CASA"
                        golsCasa < golsFora -> "FORA"
                        else -> "EMPATE"
                    }

                    val registro = hashMapOf<String, Any>(
                        "idJogo" to id,
                        "temporada" to jogo.optInt("temporada", 0),
                        "rodada" to jogo.optString("rodada"),
                        "data" to jogo.optString("data"),
                        "mandante" to casa,
                        "visitante" to fora,
                        "probCasa" to analise.probCasa,
                        "probEmpate" to analise.probEmpate,
                        "probFora" to analise.probFora,
                        "probCasaExata" to analise.probCasaExata,
                        "probEmpateExata" to analise.probEmpateExata,
                        "probForaExata" to analise.probForaExata,
                        "eloProbCasa" to eloCasa,
                        "eloProbEmpate" to eloEmpate,
                        "eloProbFora" to eloFora,
                        "combinadoProbCasa" to combinadoCasa,
                        "combinadoProbEmpate" to combinadoEmpate,
                        "combinadoProbFora" to combinadoFora,
                        "golsEsperadosCasa" to analise.golsEsperadosCasa,
                        "golsEsperadosFora" to analise.golsEsperadosFora,
                        "placarCasa" to analise.placarCasa,
                        "placarFora" to analise.placarFora,
                        "resultadoPrevisto" to previsto,
                        "golsReaisCasa" to golsCasa,
                        "golsReaisFora" to golsFora,
                        "resultadoReal" to real,
                        "acertouResultado" to (previsto == real),
                        "acertouPlacar" to (
                            analise.placarCasa == golsCasa &&
                                analise.placarFora == golsFora
                            ),
                        "finalizado" to true,
                        "origem" to "SIMULACAO_HISTORICA"
                    )

                    operacoes.add(
                        colecao(uid).document(id) to registro
                    )
                }

                if (operacoes.isEmpty()) {
                    mainHandler.post { callback(0, null) }
                    return@execute
                }

                // Firestore aceita no máximo 500 operações por lote.
                // Usamos 450 para manter margem e enviamos os lotes em sequência.
                fun confirmarLote(indiceInicial: Int, totalGravado: Int) {
                    if (indiceInicial >= operacoes.size) {
                        callback(totalGravado, null)
                        return
                    }

                    val indiceFinal = minOf(indiceInicial + 450, operacoes.size)
                    val lote = db.batch()

                    for (indice in indiceInicial until indiceFinal) {
                        val (referencia, registro) = operacoes[indice]
                        lote.set(referencia, registro)
                    }

                    lote.commit()
                        .addOnSuccessListener {
                            confirmarLote(
                                indiceFinal,
                                totalGravado + (indiceFinal - indiceInicial)
                            )
                        }
                        .addOnFailureListener { erro ->
                            callback(
                                totalGravado,
                                erro.message ?: "Erro ao salvar lote da simulação histórica."
                            )
                        }
                }

                confirmarLote(0, 0)
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
        // Indexa os jogos uma única vez. Antes, cada documento do Firestore
        // percorria novamente toda a lista de partidas (custo quadrático).
        val jogosPorId = HashMap<String, JSONObject>(jogos.size * 2)
        val jogosPorChave = HashMap<String, JSONObject>(jogos.size * 2)
        jogos.forEach { jogo ->
            val id = jogo.optString("id")
            if (id.isNotBlank()) jogosPorId[id] = jogo
            val casa = jogo.optJSONObject("mandante")?.optString("nome", "").orEmpty()
            val fora = jogo.optJSONObject("visitante")?.optString("nome", "").orEmpty()
            val chave = "${jogo.optString("rodada")}_${jogo.optString("data")}_${casa}_${fora}"
            jogosPorChave[chave] = jogo
        }

        colecao(uid).get()
            .addOnSuccessListener { documentos ->
                // Toda conversão, comparação e preparação de gravação acontece
                // fora da thread principal para a aba abrir sem congelar.
                leituraExecutor.execute {
                    val atualizados = mutableListOf<JSONObject>()
                    val operacoes = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any>>>()

                    documentos.documents.forEach { documento ->
                        val dados = documento.data ?: return@forEach
                        val palpite = try {
                            JSONObject(dados)
                        } catch (_: Exception) {
                            return@forEach
                        }
                        val jaEstavaFinalizado = palpite.optBoolean("finalizado")

                        if (!jaEstavaFinalizado) {
                            val id = palpite.optString("idJogo")
                            val jogoReal = jogosPorId[id] ?: jogosPorChave[id]
                            if (jogoReal != null) {
                                val situacao = jogoReal.optString("situacao").uppercase()
                                if (situacao in listOf("FINISHED", "FINALIZADO", "AWARDED")) {
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
                            val resultadoAnterior = palpite.optString("resultadoReal")
                            val acertouResultadoAnterior = palpite.optBoolean("acertouResultado")
                            val acertouPlacarAnterior = palpite.optBoolean("acertouPlacar")
                            val usuarioAcertouResultadoAnterior = palpite.optBoolean("usuarioAcertouResultado")
                            val usuarioAcertouPlacarAnterior = palpite.optBoolean("usuarioAcertouPlacar")
                            val tinhaMetricasUsuario = !palpite.has("usuarioPlacarCasa") ||
                                (palpite.has("usuarioResultadoReal") &&
                                    palpite.has("usuarioAcertouResultado") &&
                                    palpite.has("usuarioAcertouPlacar"))
                            val temPalpiteMotor = palpite.has("placarCasa") && palpite.has("placarFora")
                            val tinhaMetricas = !temPalpiteMotor ||
                                (palpite.has("resultadoReal") &&
                                    palpite.has("acertouResultado") &&
                                    palpite.has("acertouPlacar"))

                            avaliar(palpite)

                            val precisaSalvar = !jaEstavaFinalizado ||
                                !tinhaMetricas ||
                                !tinhaMetricasUsuario ||
                                resultadoAnterior != palpite.optString("resultadoReal") ||
                                acertouResultadoAnterior != palpite.optBoolean("acertouResultado") ||
                                acertouPlacarAnterior != palpite.optBoolean("acertouPlacar") ||
                                usuarioAcertouResultadoAnterior != palpite.optBoolean("usuarioAcertouResultado") ||
                                usuarioAcertouPlacarAnterior != palpite.optBoolean("usuarioAcertouPlacar")

                            if (precisaSalvar) {
                                operacoes.add(documento.reference to palpite.toMap())
                            }
                        }
                        atualizados.add(palpite)
                    }

                    fun concluirComErro(mensagem: String) {
                        mainHandler.post { callback(null, mensagem) }
                    }

                    fun confirmarLote(indiceInicial: Int) {
                        if (indiceInicial >= operacoes.size) {
                            mainHandler.post { callback(atualizados, null) }
                            return
                        }
                        val indiceFinal = minOf(indiceInicial + 450, operacoes.size)
                        val lote = db.batch()
                        for (indice in indiceInicial until indiceFinal) {
                            val (referencia, registro) = operacoes[indice]
                            lote.set(referencia, registro, SetOptions.merge())
                        }
                        lote.commit()
                            .addOnSuccessListener {
                                confirmarLote(indiceFinal)
                            }
                            .addOnFailureListener { erro ->
                                concluirComErro(erro.message ?: "Erro ao atualizar histórico.")
                            }
                    }

                    if (operacoes.isEmpty()) {
                        mainHandler.post { callback(atualizados, null) }
                    } else {
                        confirmarLote(0)
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
        if (golsCasa < 0 || golsFora < 0) return

        val resultadoReal = when {
            golsCasa > golsFora -> "CASA"
            golsCasa < golsFora -> "FORA"
            else -> "EMPATE"
        }

        // Avalia o palpite do motor somente quando ele existe no documento.
        val previstoCasa = palpite.optInt("placarCasa", -1)
        val previstoFora = palpite.optInt("placarFora", -1)
        if (previstoCasa >= 0 && previstoFora >= 0) {
            // O resultado 1X2 é derivado do mesmo placar exibido no cartão.
            // Assim, placar 1 x 1 nunca pode ser contado como vitória da casa.
            val resultadoPrevisto = when {
                previstoCasa > previstoFora -> "CASA"
                previstoCasa < previstoFora -> "FORA"
                else -> "EMPATE"
            }
            palpite.put("resultadoPrevisto", resultadoPrevisto)
            palpite.put("resultadoReal", resultadoReal)
            palpite.put("acertouResultado", resultadoPrevisto == resultadoReal)
            palpite.put("acertouPlacar", previstoCasa == golsCasa && previstoFora == golsFora)
        }

        // Avalia de forma independente o palpite digitado pelo usuário.
        if (palpite.has("usuarioPlacarCasa") && palpite.has("usuarioPlacarFora")) {
            val usuarioCasa = palpite.optInt("usuarioPlacarCasa", -1)
            val usuarioFora = palpite.optInt("usuarioPlacarFora", -1)
            if (usuarioCasa >= 0 && usuarioFora >= 0) {
                val resultadoUsuario = when {
                    usuarioCasa > usuarioFora -> "CASA"
                    usuarioCasa < usuarioFora -> "FORA"
                    else -> "EMPATE"
                }
                palpite.put("usuarioResultadoPrevisto", resultadoUsuario)
                palpite.put("usuarioResultadoReal", resultadoReal)
                palpite.put("usuarioAcertouResultado", resultadoUsuario == resultadoReal)
                palpite.put("usuarioAcertouPlacar", usuarioCasa == golsCasa && usuarioFora == golsFora)
            }
        }
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
