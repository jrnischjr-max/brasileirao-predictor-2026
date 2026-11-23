
package br.com.jrnischjr.brasileiraopredictor

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Probabilidades1X2(
    val casa: Double,
    val empate: Double,
    val fora: Double
)

data class AnalisePalpite(
    val probCasa: Int,
    val probEmpate: Int,
    val probFora: Int,
    val placarCasa: Int,
    val placarFora: Int,
    val formaCasa: String,
    val formaFora: String,
    val probCasaExata: Double,
    val probEmpateExata: Double,
    val probForaExata: Double,
    val golsEsperadosCasa: Double,
    val golsEsperadosFora: Double
)

object MotorPalpites {

    private data class Partida(
        val casa: String,
        val fora: String,
        val golsCasa: Int,
        val golsFora: Int,
        val data: String
    )

    private fun partidasFinalizadas(
        jogos: List<JSONObject>
    ): List<Partida> {
        return jogos.mapNotNull { jogo ->
            val situacao = jogo.optString("situacao").uppercase()

            if (situacao !in listOf(
                    "FINISHED",
                    "FINALIZADO",
                    "AWARDED"
                )
            ) return@mapNotNull null

            val mandante = jogo.optJSONObject("mandante")
                ?.optString("nome", "") ?: ""

            val visitante = jogo.optJSONObject("visitante")
                ?.optString("nome", "") ?: ""

            val placar = jogo.optJSONObject("placar")
                ?: return@mapNotNull null

            val golsCasa = placar.optInt("mandante", -1)
            val golsFora = placar.optInt("visitante", -1)

            if (mandante.isBlank() || visitante.isBlank() ||
                golsCasa < 0 || golsFora < 0
            ) return@mapNotNull null

            Partida(
                mandante,
                visitante,
                golsCasa,
                golsFora,
                jogo.optString("data")
            )
        }.sortedBy { it.data }
    }

    private fun poisson(
        media: Double,
        gols: Int
    ): Double {
        var resultado = exp(-media)

        for (i in 1..gols) {
            resultado *= media / i
        }

        return resultado
    }

    // Experimento de calibracao do mando: altera somente a media de gols da casa.
    // Mantem o modelo Poisson independente e nao modifica os palpites oficiais.
    fun probabilidadesComFatorMandante(
        mediaCasa: Double,
        mediaFora: Double,
        fatorMandante: Double
    ): Probabilidades1X2 {
        val casaAjustada = (mediaCasa * fatorMandante).coerceIn(0.05, 5.0)
        var casa = 0.0
        var empate = 0.0
        var fora = 0.0

        for (gCasa in 0..10) {
            for (gFora in 0..10) {
                val chance = poisson(casaAjustada, gCasa) *
                    poisson(mediaFora, gFora)
                when {
                    gCasa > gFora -> casa += chance
                    gCasa == gFora -> empate += chance
                    else -> fora += chance
                }
            }
        }

        val total = casa + empate + fora
        if (total <= 0.0) return Probabilidades1X2(0.0, 0.0, 0.0)
        return Probabilidades1X2(
            casa = casa / total * 100.0,
            empate = empate / total * 100.0,
            fora = fora / total * 100.0
        )
    }

    // Dixon-Coles: ajusta apenas os quatro placares baixos,
    // preservando as médias de gols e normalizando as probabilidades.
    fun probabilidadesDixonColes(
        mediaCasa: Double,
        mediaFora: Double,
        rho: Double
    ): Probabilidades1X2 {
        var casa = 0.0
        var empate = 0.0
        var fora = 0.0

        for (gCasa in 0..10) {
            for (gFora in 0..10) {
                var fator = 1.0
                if (gCasa == 0 && gFora == 0) {
                    fator = 1.0 - mediaCasa * mediaFora * rho
                } else if (gCasa == 0 && gFora == 1) {
                    fator = 1.0 + mediaCasa * rho
                } else if (gCasa == 1 && gFora == 0) {
                    fator = 1.0 + mediaFora * rho
                } else if (gCasa == 1 && gFora == 1) {
                    fator = 1.0 - rho
                }

                val chance = poisson(mediaCasa, gCasa) *
                    poisson(mediaFora, gFora) * fator.coerceAtLeast(0.0)

                when {
                    gCasa > gFora -> casa += chance
                    gCasa == gFora -> empate += chance
                    else -> fora += chance
                }
            }
        }

        val total = casa + empate + fora
        if (total <= 0.0) return Probabilidades1X2(0.0, 0.0, 0.0)
        return Probabilidades1X2(
            casa = casa / total * 100.0,
            empate = empate / total * 100.0,
            fora = fora / total * 100.0
        )
    }

    private fun partidasDoTime(
        nome: String,
        partidas: List<Partida>
    ): List<Partida> {
        return partidas.filter {
            it.casa.equals(nome, true) ||
            it.fora.equals(nome, true)
        }
    }

    // Classificação reconstruída somente com jogos anteriores à partida-alvo.
    // Não usa a tabela atual, que poderia conter resultados posteriores.
    private fun estatisticasHistoricas(
        partidas: List<Partida>
    ): List<JSONObject> {
        val estatisticas = linkedMapOf<String, IntArray>()

        partidas.forEach { partida ->
            val casa = estatisticas.getOrPut(partida.casa) { intArrayOf(0, 0) }
            val fora = estatisticas.getOrPut(partida.fora) { intArrayOf(0, 0) }
            casa[0]++
            fora[0]++

            when {
                partida.golsCasa > partida.golsFora -> casa[1] += 3
                partida.golsCasa < partida.golsFora -> fora[1] += 3
                else -> {
                    casa[1]++
                    fora[1]++
                }
            }
        }

        return estatisticas.map { (nome, dados) ->
            JSONObject()
                .put("nome", nome)
                .put("jogos", dados[0])
                .put("pontos", dados[1])
        }
    }

    private fun formaRecente(
        nome: String,
        partidas: List<Partida>,
        clubes: List<JSONObject>
    ): Pair<Double, String> {

        val recentes = partidasDoTime(nome, partidas)
            .takeLast(5)
            .reversed()

        if (recentes.isEmpty()) {
            return 1.0 to "Sem histórico"
        }

        val mediaPontosLiga = clubes.mapNotNull {
            val jogos = it.optInt("jogos")
            if (jogos > 0) it.optDouble("pontos") / jogos
            else null
        }.average().takeIf { it.isFinite() && it > 0 }
            ?: 1.5

        var pontosPonderados = 0.0
        var pesoTotal = 0.0
        var saldoGols = 0.0
        var forma = ""

        recentes.forEachIndexed { indice, partida ->
            val peso = (5 - indice).toDouble()

            val emCasa = partida.casa.equals(nome, true)

            val golsFeitos = if (emCasa)
                partida.golsCasa else partida.golsFora

            val golsSofridos = if (emCasa)
                partida.golsFora else partida.golsCasa

            val pontos = when {
                golsFeitos > golsSofridos -> 3.0
                golsFeitos == golsSofridos -> 1.0
                else -> 0.0
            }

            val adversario = if (emCasa)
                partida.fora else partida.casa

            val dadosAdversario = clubes.firstOrNull {
                it.optString("nome").equals(adversario, true)
            }

            val jogosAdversario =
                dadosAdversario?.optInt("jogos") ?: 0

            val forcaAdversario = if (jogosAdversario > 0) {
                dadosAdversario!!.optDouble("pontos") /
                    jogosAdversario
            } else {
                mediaPontosLiga
            }

            val ajusteAdversario = (
                0.85 + 0.15 *
                    forcaAdversario / mediaPontosLiga
            ).coerceIn(0.85, 1.15)

            pontosPonderados += pontos * ajusteAdversario * peso
            pesoTotal += peso

            saldoGols += (golsFeitos - golsSofridos)

            forma += when {
                pontos == 3.0 -> "V"
                pontos == 1.0 -> "E"
                else -> "D"
            }
        }

        val pontosMedios = pontosPonderados / pesoTotal
        val saldoMedio = saldoGols / recentes.size

        val fator = (
            1.0 +
            (pontosMedios - 1.5) * 0.07 +
            saldoMedio * 0.035
        ).coerceIn(0.85, 1.15)

        return fator to forma
    }

    private fun fatorConfrontoDireto(
        nomeCasa: String,
        nomeFora: String,
        partidas: List<Partida>
    ): Double {
        val confrontos = partidas.filter { partida ->
            (partida.casa.equals(nomeCasa, true) &&
                partida.fora.equals(nomeFora, true)) ||
            (partida.casa.equals(nomeFora, true) &&
                partida.fora.equals(nomeCasa, true))
        }.takeLast(10)

        if (confrontos.isEmpty()) return 1.0

        var saldoPonderado = 0.0
        var pesoTotal = 0.0

        confrontos.forEachIndexed { indice, partida ->
            val peso = (indice + 1).toDouble()
            val casaEraMandante = partida.casa.equals(nomeCasa, true)
            val golsTimeCasa = if (casaEraMandante) partida.golsCasa else partida.golsFora
            val golsTimeFora = if (casaEraMandante) partida.golsFora else partida.golsCasa

            val resultado = when {
                golsTimeCasa > golsTimeFora -> 1.0
                golsTimeCasa < golsTimeFora -> -1.0
                else -> 0.0
            }

            saldoPonderado += resultado * peso
            pesoTotal += peso
        }

        val equilibrio = if (pesoTotal > 0.0) saldoPonderado / pesoTotal else 0.0
        return (1.0 + equilibrio * 0.035).coerceIn(0.965, 1.035)
    }

    private fun media(
        valores: List<Double>,
        padrao: Double
    ): Double {
        return valores.average()
            .takeIf { valores.isNotEmpty() && it.isFinite() }
            ?: padrao
    }

    // Modelo Elo experimental: usa somente partidas anteriores à data-alvo.
    // O empate é estimado pela frequência histórica anterior, suavizada para evitar extremos.
    fun probabilidadesEloHistorico(
        jogo: JSONObject,
        todosJogos: List<JSONObject>
    ): Probabilidades1X2? {
        val nomeCasa = jogo.optJSONObject("mandante")?.optString("nome", "").orEmpty()
        val nomeFora = jogo.optJSONObject("visitante")?.optString("nome", "").orEmpty()
        val dataAlvo = jogo.optString("data").take(10)
        if (nomeCasa.isBlank() || nomeFora.isBlank() || dataAlvo.isBlank()) return null

        val anteriores = partidasFinalizadas(todosJogos)
            .filter { it.data.take(10).isNotBlank() && it.data.take(10) < dataAlvo }
        if (anteriores.isEmpty()) return null

        val ratings = mutableMapOf<String, Double>()
        var empates = 0
        var total = 0
        val k = 20.0
        val vantagemMandante = 65.0

        anteriores.forEach { partida ->
            val ratingCasa = ratings.getOrPut(partida.casa.lowercase()) { 1500.0 }
            val ratingFora = ratings.getOrPut(partida.fora.lowercase()) { 1500.0 }
            val diferenca = ratingCasa + vantagemMandante - ratingFora
            val esperadoCasa = 1.0 / (1.0 + exp(-kotlin.math.ln(10.0) * diferenca / 400.0))
            val realCasa = when {
                partida.golsCasa > partida.golsFora -> 1.0
                partida.golsCasa == partida.golsFora -> 0.5
                else -> 0.0
            }
            val ajuste = k * (realCasa - esperadoCasa)
            ratings[partida.casa.lowercase()] = ratingCasa + ajuste
            ratings[partida.fora.lowercase()] = ratingFora - ajuste
            if (realCasa == 0.5) empates++
            total++
        }

        val ratingCasa = ratings[nomeCasa.lowercase()] ?: 1500.0
        val ratingFora = ratings[nomeFora.lowercase()] ?: 1500.0
        val diferenca = ratingCasa + vantagemMandante - ratingFora
        val esperadoCasa = 1.0 / (1.0 + exp(-kotlin.math.ln(10.0) * diferenca / 400.0))
        val taxaEmpate = (empates + 5.4) / (total + 20.0)
        val chanceDecisivaCasa = ((esperadoCasa - taxaEmpate / 2.0) / (1.0 - taxaEmpate))
            .coerceIn(0.0, 1.0)
        val casa = (1.0 - taxaEmpate) * chanceDecisivaCasa
        val fora = (1.0 - taxaEmpate) * (1.0 - chanceDecisivaCasa)
        return Probabilidades1X2(
            casa = casa * 100.0,
            empate = taxaEmpate * 100.0,
            fora = fora * 100.0
        )
    }

    // Reconstitui o contexto disponível antes da partida. Jogos do mesmo dia
    // são excluídos para evitar usar resultados que talvez tenham ocorrido depois.
    fun analisarHistorico(
        jogo: JSONObject,
        todosJogos: List<JSONObject>
    ): AnalisePalpite? {
        val dataAlvo = jogo.optString("data").take(10)
        if (dataAlvo.isBlank()) return null

        val anteriores = todosJogos.filter { partida ->
            val dataPartida = partida.optString("data").take(10)
            dataPartida.isNotBlank() && dataPartida < dataAlvo
        }

        val nomes = todosJogos.flatMap { partida ->
            listOf(
                partida.optJSONObject("mandante")?.optString("nome", "").orEmpty(),
                partida.optJSONObject("visitante")?.optString("nome", "").orEmpty()
            )
        }.filter { it.isNotBlank() }.distinct()

        val estatisticas = nomes.map { nome ->
            var jogosTime = 0
            var pontosTime = 0
            anteriores.forEach { partida ->
                val situacao = partida.optString("situacao").uppercase()
                val placar = partida.optJSONObject("placar")
                val casa = partida.optJSONObject("mandante")?.optString("nome", "").orEmpty()
                val fora = partida.optJSONObject("visitante")?.optString("nome", "").orEmpty()
                val gc = placar?.optInt("mandante", -1) ?: -1
                val gf = placar?.optInt("visitante", -1) ?: -1
                if (situacao in listOf("FINISHED", "FINALIZADO", "AWARDED") &&
                    gc >= 0 && gf >= 0 && (casa.equals(nome, true) || fora.equals(nome, true))
                ) {
                    jogosTime++
                    pontosTime += when {
                        casa.equals(nome, true) && gc > gf -> 3
                        fora.equals(nome, true) && gf > gc -> 3
                        gc == gf -> 1
                        else -> 0
                    }
                }
            }
            JSONObject().put("nome", nome).put("jogos", jogosTime).put("pontos", pontosTime)
        }

        return analisar(jogo, anteriores, estatisticas)
    }

    fun analisar(
        jogo: JSONObject,
        todosJogos: List<JSONObject>,
        clubes: List<JSONObject>
    ): AnalisePalpite? {

        val nomeCasa = jogo.optJSONObject("mandante")
            ?.optString("nome", "") ?: ""

        val nomeFora = jogo.optJSONObject("visitante")
            ?.optString("nome", "") ?: ""

        if (nomeCasa.isBlank() || nomeFora.isBlank()) {
            return null
        }

        val timeCasa = clubes.firstOrNull {
            it.optString("nome").equals(nomeCasa, true)
        } ?: return null

        val timeFora = clubes.firstOrNull {
            it.optString("nome").equals(nomeFora, true)
        } ?: return null

        // Limita toda a base à data anterior ao jogo previsto.
        // Isso impede vazamento de resultados futuros ao combinar várias temporadas.
        val dataAlvo = jogo.optString("data").take(10)
        if (dataAlvo.isBlank()) return null

        // Elo passa a ser o modelo principal de resultado 1X2.
        // Considera apenas partidas finalizadas anteriores à data-alvo.
        val probabilidadesElo = probabilidadesEloHistorico(jogo, todosJogos)
            ?: return null

        val partidas = partidasFinalizadas(todosJogos)
            .filter { it.data.take(10).isNotBlank() && it.data.take(10) < dataAlvo }

        if (partidas.isEmpty()) return null

        // Força e forma dos adversários calculadas no corte temporal da partida.
        val clubesHistoricos = estatisticasHistoricas(partidas)

        val jogosCasa = partidasDoTime(nomeCasa, partidas)
            .filter { it.casa.equals(nomeCasa, true) }

        val jogosFora = partidasDoTime(nomeFora, partidas)
            .filter { it.fora.equals(nomeFora, true) }

        val jogosLigaCasa = partidas.map { it.golsCasa }
        val jogosLigaFora = partidas.map { it.golsFora }

        val mediaLigaCasa = media(
            jogosLigaCasa.map { it.toDouble() },
            1.35
        )

        val mediaLigaFora = media(
            jogosLigaFora.map { it.toDouble() },
            1.10
        )

        fun golsPorJogo(
            lista: List<Partida>,
            nome: String,
            feitos: Boolean
        ): Double {
            if (lista.isEmpty()) return 0.0

            val recentes = lista.takeLast(8)
            var somaPonderada = 0.0
            var pesoTotal = 0.0

            recentes.forEachIndexed { indice, partida ->
                val gols = when {
                    partida.casa.equals(nome, true) ->
                        if (feitos) partida.golsCasa else partida.golsFora
                    else ->
                        if (feitos) partida.golsFora else partida.golsCasa
                }
                val peso = (indice + 1).toDouble()
                somaPonderada += gols * peso
                pesoTotal += peso
            }

            return if (pesoTotal > 0.0) somaPonderada / pesoTotal else 0.0
        }

        // Amostras pequenas recebem ajuste em direção à média da liga.
        // Isso evita que um ou dois jogos distorçam a previsão.
        fun ajustarPelaAmostra(
            observado: Double,
            referencia: Double,
            quantidade: Int
        ): Double {
            val confiabilidade = quantidade.toDouble() / (quantidade + 4.0)
            return referencia + (observado - referencia) * confiabilidade
        }

        val ataqueCasaBruto = if (jogosCasa.isNotEmpty())
            golsPorJogo(jogosCasa, nomeCasa, true)
        else mediaLigaCasa

        val defesaCasaBruta = if (jogosCasa.isNotEmpty())
            golsPorJogo(jogosCasa, nomeCasa, false)
        else mediaLigaFora

        val ataqueForaBruto = if (jogosFora.isNotEmpty())
            golsPorJogo(jogosFora, nomeFora, true)
        else mediaLigaFora

        val defesaForaBruta = if (jogosFora.isNotEmpty())
            golsPorJogo(jogosFora, nomeFora, false)
        else mediaLigaCasa

        val ataqueCasa = ajustarPelaAmostra(ataqueCasaBruto, mediaLigaCasa, jogosCasa.size)
        val defesaCasa = ajustarPelaAmostra(defesaCasaBruta, mediaLigaFora, jogosCasa.size)
        val ataqueFora = ajustarPelaAmostra(ataqueForaBruto, mediaLigaFora, jogosFora.size)
        val defesaFora = ajustarPelaAmostra(defesaForaBruta, mediaLigaCasa, jogosFora.size)

        val fatorAtaqueCasa =
            (ataqueCasa / mediaLigaCasa).coerceIn(0.35, 2.5)

        val fatorDefesaFora =
            (defesaFora / mediaLigaCasa).coerceIn(0.35, 2.5)

        val fatorAtaqueFora =
            (ataqueFora / mediaLigaFora).coerceIn(0.35, 2.5)

        val fatorDefesaCasa =
            (defesaCasa / mediaLigaFora).coerceIn(0.35, 2.5)

        val (formaFatorCasa, formaCasa) =
            formaRecente(nomeCasa, partidas, clubesHistoricos)

        val (formaFatorFora, formaFora) =
            formaRecente(nomeFora, partidas, clubesHistoricos)

        val fatorConfronto = fatorConfrontoDireto(
            nomeCasa,
            nomeFora,
            partidas
        )

        val esperadoCasa = (
            mediaLigaCasa *
            sqrt(fatorAtaqueCasa * fatorDefesaFora) *
            formaFatorCasa *
            fatorConfronto *
            1.0
        ).coerceIn(0.15, 3.8)

        val esperadoFora = (
            mediaLigaFora *
            sqrt(fatorAtaqueFora * fatorDefesaCasa) *
            formaFatorFora *
            (2.0 - fatorConfronto) *
            1.0
        ).coerceIn(0.15, 3.8)

        var vitoriaCasa = 0.0
        var empate = 0.0
        var vitoriaFora = 0.0

        for (gCasa in 0..8) {
            for (gFora in 0..8) {
                val chance =
                    poisson(esperadoCasa, gCasa) *
                    poisson(esperadoFora, gFora)

                when {
                    gCasa > gFora -> vitoriaCasa += chance
                    gCasa == gFora -> empate += chance
                    else -> vitoriaFora += chance
                }
            }
        }

        val total = vitoriaCasa + empate + vitoriaFora

        if (total <= 0.0) return null

        // Mantém as probabilidades completas para decidir o 1X2.
        // O arredondamento acontece apenas nos valores exibidos/salvos como inteiros.
        // Probabilidades oficiais do motor: Elo.
        // Poisson permanece como estimador de placar, não do resultado 1X2.
        val probCasaExata = probabilidadesElo.casa
        val probEmpateExata = probabilidadesElo.empate
        val probForaExata = probabilidadesElo.fora

        val pCasa = probCasaExata.roundToInt()
        val pEmpate = probEmpateExata.roundToInt()
        val pFora = 100 - pCasa - pEmpate

        return AnalisePalpite(
            probCasa = pCasa,
            probEmpate = pEmpate,
            probFora = pFora,
            placarCasa = esperadoCasa.roundToInt(),
            placarFora = esperadoFora.roundToInt(),
            formaCasa = formaCasa,
            formaFora = formaFora,
            probCasaExata = probCasaExata,
            probEmpateExata = probEmpateExata,
            probForaExata = probForaExata,
            golsEsperadosCasa = esperadoCasa,
            golsEsperadosFora = esperadoFora
        )
    }
}
