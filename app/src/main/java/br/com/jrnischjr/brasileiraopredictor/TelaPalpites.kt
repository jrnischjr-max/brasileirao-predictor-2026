package br.com.jrnischjr.brasileiraopredictor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import androidx.compose.ui.Alignment

@Composable
fun TelaPalpites(
    corDestaque: Color,
    corCartao: Color,
    uid: String
) {
    val context = LocalContext.current
    val jogos = remember { mutableStateListOf<JSONObject>() }
    val clubes = remember { mutableStateListOf<JSONObject>() }
    val historico = remember { mutableStateListOf<JSONObject>() }

    var carregando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf("") }
    var jogosProntos by remember { mutableStateOf(false) }
    var tabelaPronta by remember { mutableStateOf(false) }
    var historicoCarregando by remember { mutableStateOf(false) }
    var erroHistorico by remember { mutableStateOf("") }
    var salvandoPalpite by remember { mutableStateOf(false) }
    var salvandoPalpiteUsuario by remember { mutableStateOf(false) }

    var rodadaSelecionada by remember { mutableStateOf(0) }
    var rodadaMenuAberto by remember { mutableStateOf(false) }

    fun carregarHistorico() {
        if (uid.isBlank()) {
            historicoCarregando = false
            erroHistorico = "Faça login para acessar seu histórico."
            return
        }

        historicoCarregando = true
        HistoricoPalpitesNuvem.carregar(uid, context) { lista, mensagem ->
            historicoCarregando = false
            if (lista != null) {
                historico.clear()
                historico.addAll(lista)
                erroHistorico = ""
            } else {
                erroHistorico = mensagem ?: "Não foi possível carregar o histórico."
            }
        }
    }

    fun atualizarHistorico() {
        if (uid.isBlank()) return

        HistoricoPalpitesNuvem.atualizarResultados(
            uid,
            jogos.toList()
        ) { atualizados, mensagem ->
            if (atualizados != null) {
                historico.clear()
                historico.addAll(atualizados)
                erroHistorico = ""
            } else {
                erroHistorico = mensagem ?: "Não foi possível atualizar o histórico."
            }
        }
    }

    fun carregarDados() {
        carregando = true
        erro = ""
        jogosProntos = false
        tabelaPronta = false

        DadosBrasileirao.buscarJogos(context) { dados, mensagem ->
            if (dados != null) {
                jogos.clear()
                val lista = dados.optJSONArray("jogos")

                if (lista != null) {
                    for (i in 0 until lista.length()) {
                        lista.optJSONObject(i)?.let { jogos.add(it) }
                    }
                }

                jogosProntos = true
            } else {
                erro = mensagem ?: "Erro ao buscar jogos."
                carregando = false
            }
        }

        DadosBrasileirao.buscarClassificacao(context) { dados, mensagem ->
            if (dados != null) {
                clubes.clear()
                val lista = dados.optJSONArray("classificacao")

                if (lista != null) {
                    for (i in 0 until lista.length()) {
                        lista.optJSONObject(i)?.let { clubes.add(it) }
                    }
                }

                tabelaPronta = true
            } else {
                erro = mensagem ?: "Erro ao buscar classificação."
                carregando = false
            }
        }

    }

    // O motor recebe exclusivamente partidas do Brasileirão 2026.
    // Apenas resultados já finalizados antes da data do jogo entram no cálculo.
    val todosJogosMotor = remember(jogos.size) {
        jogos.filter { jogo ->
            val temporada = jogo.optInt(
                "temporada",
                jogo.optString("data").take(4).toIntOrNull() ?: 0
            )
            temporada == 2026
        }.sortedBy { it.optString("data") }
    }

    LaunchedEffect(Unit) {
        carregarDados()
    }

    // Recupera automaticamente os palpites da conta sempre que a tela abrir
    // ou quando o usuário autenticado mudar.
    LaunchedEffect(uid) {
        if (uid.isNotBlank()) carregarHistorico()
    }

    LaunchedEffect(jogosProntos, tabelaPronta) {
        if (jogosProntos && tabelaPronta) {
            carregando = false

            if (jogos.isEmpty() || clubes.isEmpty()) {
                erro = "Não há dados suficientes para gerar os palpites."
            }
        }
    }

    fun formatarData(data: String): String {
        return try {
            val entrada = java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss",
                java.util.Locale.US
            ).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }

            val convertida = entrada.parse(data.take(19))
                ?: return "Data a confirmar"

            val saida = java.text.SimpleDateFormat(
                "dd/MM/yyyy • HH:mm",
                java.util.Locale("pt", "BR")
            ).apply {
                timeZone = java.util.TimeZone.getTimeZone("America/Sao_Paulo")
            }

            saida.format(convertida)
        } catch (e: Exception) {
            "Data a confirmar"
        }
    }

    fun partidaComecou(data: String): Boolean {
        val inicio = try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
                isLenient = false
            }.parse(data.take(19))?.time
        } catch (_: Exception) {
            null
        }
        return inicio == null || System.currentTimeMillis() >= inicio
    }

    val jogosPendentes = jogos.filter {
        val situacao = it.optString("situacao").uppercase()

        situacao != "FINISHED" &&
        situacao != "FINALIZADO" &&
        situacao != "AWARDED"
    }

    // Mantém as 38 rodadas disponíveis também na aba Palpites,
    // mesmo quando a API ainda não retornou partidas de alguma rodada.
    val rodadasDisponiveis = (1..38).toList()

    val rodadaAtual = jogosPendentes
        .minOfOrNull { it.optInt("rodada") }
        ?: rodadasDisponiveis.firstOrNull()
        ?: 0

    val rodadaExibida = if (rodadaSelecionada in rodadasDisponiveis) {
        rodadaSelecionada
    } else {
        rodadaAtual
    }

    val proximosJogos = jogosPendentes
        .filter { it.optInt("rodada") == rodadaExibida }
        .sortedBy { it.optString("data") }

    val finalizados = historico.filter {
        it.optBoolean("finalizado") &&
            it.optString("origem") != "SIMULACAO_HISTORICA" &&
            it.has("placarCasa") && it.has("placarFora")
    }

    val finalizadosUsuario = historico.filter {
        it.optBoolean("finalizado") && it.has("usuarioPlacarCasa") && it.has("usuarioPlacarFora")
    }
    val acertosUsuario = finalizadosUsuario.count { it.optBoolean("usuarioAcertouResultado") }
    val acertosPlacarUsuario = finalizadosUsuario.count { it.optBoolean("usuarioAcertouPlacar") }
    val taxaUsuario = if (finalizadosUsuario.isNotEmpty()) acertosUsuario * 100 / finalizadosUsuario.size else 0
    val taxaPlacarUsuario = if (finalizadosUsuario.isNotEmpty()) acertosPlacarUsuario * 100 / finalizadosUsuario.size else 0

    // Acerto de resultado significa acertar Casa/Empate/Fora (1X2).
    val acertos = finalizados.count {
        it.optBoolean("acertouResultado")
    }

    val taxaAcerto = if (finalizados.isNotEmpty()) {
        (acertos * 100) / finalizados.size
    } else {
        0
    }

    // Placar exato é uma métrica independente e mais rigorosa.
    val acertosPlacar = finalizados.count {
        it.optBoolean("acertouPlacar")
    }

    val taxaPlacar = if (finalizados.isNotEmpty()) {
        (acertosPlacar * 100) / finalizados.size
    } else {
        0
    }


    // A tela principal já controla a rolagem vertical. Não criar uma
    // segunda área vertical rolável aqui, pois o Compose pode medir a
    // rolagem interna com altura ilimitada e encerrar o app.
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {

        Text(
            "🎯 PALPITES INTELIGENTES",
            color = corDestaque,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Previsões estatísticas da rodada selecionada",
            color = Color.LightGray,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(15.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = corCartao)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "COMO FUNCIONA",
                    color = corDestaque,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    "O motor analisa os últimos cinco jogos, o desempenho em casa e fora, gols marcados e sofridos e a força dos adversários. Um modelo estatístico de Poisson calcula as probabilidades.",
                    color = Color.White,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    "As porcentagens são estimativas estatísticas, não garantias de resultado.",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(15.dp))

        if (carregando) {
            CircularProgressIndicator(color = corDestaque)

            Text(
                "Analisando os clubes e partidas...",
                color = Color.White,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        if (erro.isNotEmpty()) {
            Text(erro, color = Color.White)

            Spacer(modifier = Modifier.height(10.dp))

            Button(onClick = { carregarDados() }) {
                Text("ATUALIZAR DADOS")
            }
        }

        if (!carregando && erro.isEmpty() && jogosProntos && tabelaPronta) {

            Text(
                "ESCOLHA A RODADA",
                color = corDestaque,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedButton(
                onClick = { rodadaMenuAberto = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("RODADA $rodadaExibida  ▼")
            }

            if (rodadaMenuAberto) {
                AlertDialog(
                    onDismissRequest = { rodadaMenuAberto = false },
                    title = { Text("Selecionar rodada") },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 460.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            rodadasDisponiveis.forEach { numeroRodada ->
                                TextButton(
                                    onClick = {
                                        rodadaSelecionada = numeroRodada
                                        rodadaMenuAberto = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "RODADA $numeroRodada",
                                        modifier = Modifier.fillMaxWidth(),
                                        color = if (rodadaSelecionada == numeroRodada)
                                            corDestaque else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { rodadaMenuAberto = false }) {
                            Text("FECHAR")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (proximosJogos.isEmpty()) {
                Text(
                    "Não há partidas pendentes nesta rodada.",
                    color = Color.White
                )
            }

            proximosJogos.forEach { jogo ->

                val mandante = nomeExibicaoClube(jogo.optJSONObject("mandante")
                    ?.optString("nome", "") ?: "")

                val visitante = nomeExibicaoClube(jogo.optJSONObject("visitante")
                    ?.optString("nome", "") ?: "")

                val idJogo = jogo.optString("id").ifBlank {
                    "${jogo.optString("rodada")}_${jogo.optString("data")}_${mandante}_${visitante}"
                }

                // Mantém a análise estatística em memória durante recomposições
                // da tela, evitando recalcular o motor ao rolar ou editar um placar.
                val analise = remember(idJogo, todosJogosMotor, clubes.size) {
                    MotorPalpites.analisar(
                        jogo = jogo,
                        todosJogos = todosJogosMotor,
                        clubes = clubes.toList()
                    )
                }

                if (analise != null) {

                    val registroDoJogo = historico.firstOrNull {
                        it.optString("idJogo") == idJogo
                    }
                    val jaSalvo = registroDoJogo?.has("probCasaExata") == true
                    val usuarioJaSalvou = registroDoJogo?.has("usuarioPlacarCasa") == true
                    val partidaBloqueada = partidaComecou(jogo.optString("data"))
                    var golsUsuarioCasa by remember(idJogo, registroDoJogo?.optInt("usuarioPlacarCasa", -1)) {
                        mutableStateOf(registroDoJogo?.optInt("usuarioPlacarCasa", -1)?.takeIf { it >= 0 }?.toString().orEmpty())
                    }
                    var golsUsuarioFora by remember(idJogo, registroDoJogo?.optInt("usuarioPlacarFora", -1)) {
                        mutableStateOf(registroDoJogo?.optInt("usuarioPlacarFora", -1)?.takeIf { it >= 0 }?.toString().orEmpty())
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = corCartao
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Text(
                                "RODADA ${jogo.optString("rodada")}",
                                color = corDestaque,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )

                            Text(
                                formatarData(jogo.optString("data")),
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            
Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically
) {
    Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EscudoClube(
            url = jogo.optJSONObject("mandante")?.optString("escudo"),
            nome = mandante,
            tamanho = 34.dp
        )

        Spacer(modifier = Modifier.width(6.dp))

        Column {
            Text(
                mandante,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )

            Text(
                "Forma: ${analise.formaCasa}",
                color = corDestaque,
                fontSize = 10.sp
            )
        }
    }

    Text(
        "X",
        color = corDestaque,
        fontWeight = FontWeight.Black,
        fontSize = 18.sp
    )

    Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Text(
                visitante,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )

            Text(
                "Forma: ${analise.formaFora}",
                color = corDestaque,
                fontSize = 10.sp
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        EscudoClube(
            url = jogo.optJSONObject("visitante")?.optString("escudo"),
            nome = visitante,
            tamanho = 34.dp
        )
    }
}


                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                "PROBABILIDADES ESTIMADAS",
                                color = corDestaque,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Casa", color = Color.White, fontSize = 12.sp)
                                Text("Empate", color = Color.White, fontSize = 12.sp)
                                Text("Fora", color = Color.White, fontSize = 12.sp)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${analise.probCasa}%",
                                    color = corDestaque,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    "${analise.probEmpate}%",
                                    color = corDestaque,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    "${analise.probFora}%",
                                    color = corDestaque,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                "🎯 PLACAR ESTIMADO: ${analise.placarCasa} x ${analise.placarFora}",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Divider(color = corDestaque.copy(alpha = 0.45f))

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                "🙋 SEU PALPITE",
                                color = corDestaque,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                "Faça seu próprio placar. Ele fica salvo na sua conta e será avaliado após o jogo.",
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = golsUsuarioCasa,
                                    onValueChange = { valor ->
                                        if (valor.length <= 2 && valor.all { it.isDigit() }) golsUsuarioCasa = valor
                                    },
                                    label = { Text("Gols casa") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    enabled = !partidaBloqueada,
                                    textStyle = LocalTextStyle.current.copy(
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 22.sp
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        disabledTextColor = Color.White,
                                        cursorColor = Color.White,
                                        focusedLabelColor = Color.White,
                                        unfocusedLabelColor = Color.LightGray,
                                        focusedBorderColor = corDestaque,
                                        unfocusedBorderColor = corDestaque.copy(alpha = 0.65f),
                                        disabledBorderColor = corDestaque.copy(alpha = 0.35f)
                                    ),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                OutlinedTextField(
                                    value = golsUsuarioFora,
                                    onValueChange = { valor ->
                                        if (valor.length <= 2 && valor.all { it.isDigit() }) golsUsuarioFora = valor
                                    },
                                    label = { Text("Gols fora") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    enabled = !partidaBloqueada,
                                    textStyle = LocalTextStyle.current.copy(
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 22.sp
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        disabledTextColor = Color.White,
                                        cursorColor = Color.White,
                                        focusedLabelColor = Color.White,
                                        unfocusedLabelColor = Color.LightGray,
                                        focusedBorderColor = corDestaque,
                                        unfocusedBorderColor = corDestaque.copy(alpha = 0.65f),
                                        disabledBorderColor = corDestaque.copy(alpha = 0.35f)
                                    ),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                            if (usuarioJaSalvou) {
                                Text(
                                    "Seu palpite salvo: ${registroDoJogo?.optInt("usuarioPlacarCasa")} x ${registroDoJogo?.optInt("usuarioPlacarFora")}",
                                    color = Color(0xFF81C784),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            Button(
                                onClick = {
                                    val casa = golsUsuarioCasa.toIntOrNull()
                                    val fora = golsUsuarioFora.toIntOrNull()
                                    if (casa == null || fora == null || casa !in 0..20 || fora !in 0..20) {
                                        erroHistorico = "Preencha os dois placares com números de 0 a 20."
                                    } else {
                                        salvandoPalpiteUsuario = true
                                        erroHistorico = ""
                                        HistoricoPalpitesNuvem.salvarPalpiteUsuario(
                                            uid, jogo, casa, fora
                                        ) { sucesso, mensagem ->
                                            salvandoPalpiteUsuario = false
                                            if (sucesso) carregarHistorico()
                                            else erroHistorico = mensagem ?: "Não foi possível salvar seu palpite."
                                        }
                                    }
                                },
                                enabled = !partidaBloqueada && !salvandoPalpiteUsuario &&
                                    uid.isNotBlank() && golsUsuarioCasa.isNotBlank() && golsUsuarioFora.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = corDestaque,
                                    disabledContainerColor = Color.DarkGray
                                )
                            ) {
                                Text(
                                    when {
                                        partidaBloqueada -> "🔒 PALPITE ENCERRADO"
                                        salvandoPalpiteUsuario -> "SALVANDO SEU PALPITE..."
                                        usuarioJaSalvou -> "ATUALIZAR MEU PALPITE"
                                        else -> "💾 SALVAR MEU PALPITE"
                                    },
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    salvandoPalpite = true
                                    erroHistorico = ""

                                    HistoricoPalpitesNuvem.salvar(
                                        uid,
                                        jogo,
                                        analise
                                    ) { sucesso, mensagem ->
                                        salvandoPalpite = false
                                        if (sucesso) {
                                            carregarHistorico()
                                        } else {
                                            erroHistorico = mensagem
                                                ?: "Não foi possível salvar o palpite."
                                        }
                                    }
                                },
                                enabled = !jaSalvo && !salvandoPalpite && uid.isNotBlank() && !partidaBloqueada,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = corDestaque,
                                    disabledContainerColor = Color.DarkGray
                                )
                            ) {
                                Text(
                                    when {
                                        partidaBloqueada && !jaSalvo -> "🔒 PRAZO ENCERRADO"
                                        jaSalvo -> "✓ PALPITE DO MOTOR SALVO"
                                        salvandoPalpite -> "SALVANDO..."
                                        else -> "💾 SALVAR PALPITE"
                                    },
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = { carregarDados() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ATUALIZAR PALPITES")
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        Divider(color = corDestaque.copy(alpha = 0.5f))

        Spacer(modifier = Modifier.height(15.dp))

        Text(
            "📊 MEU HISTÓRICO",
            color = corDestaque,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { carregarHistorico() },
            enabled = !historicoCarregando && uid.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (historicoCarregando) "CARREGANDO HISTÓRICO..." else "CARREGAR MEU HISTÓRICO")
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (historicoCarregando) {
            Text(
                "Carregando histórico sob solicitação...",
                color = Color.LightGray,
                fontSize = 12.sp
            )
        }

        if (erroHistorico.isNotBlank()) {
            Text(
                erroHistorico,
                color = Color(0xFFFF8A80),
                fontSize = 12.sp
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = corCartao)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {

                Text(
                    "PALPITES SALVOS: ${historico.size}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "JOGOS FINALIZADOS: ${finalizados.size}",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )

                Text(
                    "ACERTO DO RESULTADO (1X2): $acertos de ${finalizados.size}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                Text(
                    "TAXA DE RESULTADO: $taxaAcerto%",
                    color = corDestaque,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "PLACAR EXATO: $acertosPlacar de ${finalizados.size}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                Text(
                    "TAXA DE PLACAR EXATO: $taxaPlacar%",
                    color = Color.LightGray,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                Divider(color = corDestaque.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "MEUS PALPITES: ${finalizadosUsuario.size} jogos finalizados",
                    color = corDestaque,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "ACERTO DO RESULTADO: $acertosUsuario de ${finalizadosUsuario.size} ($taxaUsuario%)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    "PLACAR EXATO: $acertosPlacarUsuario de ${finalizadosUsuario.size} ($taxaPlacarUsuario%)",
                    color = Color.White,
                    fontSize = 13.sp
                )

                Text(
                    "Resultado (1X2) considera apenas Casa, Empate ou Fora. Placar exato exige acertar os dois números.",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Spacer(modifier = Modifier.height(12.dp))

        historico.sortedByDescending {
            it.optString("data")
        }.forEach { palpite ->

            val finalizado = palpite.optBoolean("finalizado")

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = corCartao
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {

                    Text(
                        "${palpite.optString("mandante")} x ${palpite.optString("visitante")}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        if (palpite.optString("origem") == "SIMULACAO_HISTORICA")
                            "SIMULAÇÃO RETROSPECTIVA"
                        else
                            "PALPITE REAL",
                        color = corDestaque,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Palpite: ${palpite.optInt("placarCasa")} x ${palpite.optInt("placarFora")}",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )

                    if (finalizado) {
                        Text(
                            "Resultado real: ${palpite.optInt("golsReaisCasa")} x ${palpite.optInt("golsReaisFora")}",
                            color = Color.White,
                            fontSize = 12.sp
                        )

                        Text(
                            if (palpite.optBoolean("acertouResultado"))
                                "✓ Resultado acertado"
                            else
                                "✗ Resultado não acertado",
                            color = if (palpite.optBoolean("acertouResultado"))
                                corDestaque else Color.LightGray,
                            fontWeight = FontWeight.Bold
                        )

                        if (palpite.optBoolean("acertouPlacar")) {
                            Text(
                                "🏆 PLACAR EXATO!",
                                color = corDestaque,
                                fontWeight = FontWeight.Black
                            )
                        }
                    } else {
                        Text(
                            "Aguardando resultado",
                            color = corDestaque,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        if (historico.isEmpty()) {
            Text(
                "Seus palpites salvos aparecerão aqui.",
                color = Color.LightGray,
                fontSize = 12.sp
            )
        }
    }
}
