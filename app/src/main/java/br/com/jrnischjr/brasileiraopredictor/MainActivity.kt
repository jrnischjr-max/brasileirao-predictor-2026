package br.com.jrnischjr.brasileiraopredictor

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PredictorApp()
        }
    }
}

@Composable
fun PredictorApp() {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    val preferencias = remember {
        context.getSharedPreferences(
            "predictor_prefs",
            Context.MODE_PRIVATE
        )
    }

    val emailsSalvos = remember {
        mutableStateListOf<String>().apply {
            addAll(
                preferencias.getStringSet(
                    "emails_salvos",
                    emptySet()
                )?.toList() ?: emptyList()
            )
        }
    }

    val salvarEmail: (String) -> Unit = { novoEmail ->
        val atualizados = (emailsSalvos + novoEmail).distinct()

        preferencias.edit()
            .putStringSet("emails_salvos", atualizados.toSet())
            .apply()

        emailsSalvos.clear()
        emailsSalvos.addAll(atualizados)
    }

    val times = listOf(
        "Flamengo", "Palmeiras", "Corinthians",
        "São Paulo", "Santos", "Vasco",
        "Fluminense", "Botafogo", "Grêmio",
        "Internacional", "Atlético-MG", "Cruzeiro",
        "Bahia", "Fortaleza", "Sport",
        "Vitória", "Ceará", "Juventude",
        "Red Bull Bragantino", "Mirassol"
    )

    var nome by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var senha by remember { mutableStateOf("") }
    var senhaVisivel by remember { mutableStateOf(false) }
    var time by remember { mutableStateOf("") }
    var cadastro by remember { mutableStateOf(true) }
    var menuAberto by remember { mutableStateOf(false) }
    var menuEmailsAberto by remember { mutableStateOf(false) }
    var mensagem by remember { mutableStateOf("") }
    var carregando by remember { mutableStateOf(false) }

    var usuarioLogado by remember {
        mutableStateOf(auth.currentUser != null)
    }

    var nomeUsuario by remember { mutableStateOf("") }
    var biometriaLiberada by remember { mutableStateOf(false) }
    var autenticacaoIniciada by remember { mutableStateOf(false) }

    val fundo = when (time) {
        "Flamengo" -> Color(0xFFC8102E)
        "Palmeiras" -> Color(0xFF006437)
        "Corinthians" -> Color(0xFF292929)
        "São Paulo" -> Color(0xFFB00020)
        "Grêmio" -> Color(0xFF07529A)
        "Internacional" -> Color(0xFFC62828)
        "Santos" -> Color(0xFF333333)
        "Vasco" -> Color(0xFF202020)
        "Cruzeiro" -> Color(0xFF0755A5)
        "Fluminense" -> Color(0xFF7A1025)
        else -> Color(0xFF063B2B)
    }

    val iniciarBiometria: () -> Unit = {
        if (!autenticacaoIniciada && !biometriaLiberada) {
            if (activity == null) {
                mensagem = "Não foi possível iniciar a autenticação."
            } else {
                autenticacaoIniciada = true
                mensagem = ""

                val autenticadores =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    } else {
                        BiometricManager.Authenticators.BIOMETRIC_WEAK
                    }

                val gerente = BiometricManager.from(activity)
                val disponibilidade =
                    gerente.canAuthenticate(autenticadores)

                if (
                    disponibilidade !=
                    BiometricManager.BIOMETRIC_SUCCESS
                ) {
                    autenticacaoIniciada = false

                    mensagem = when (disponibilidade) {
                        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                            "Este aparelho não possui leitor biométrico."

                        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                            "Leitor biométrico temporariamente indisponível."

                        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                            "Cadastre uma digital ou configure o bloqueio do celular."

                        else ->
                            "Não foi possível utilizar a autenticação deste aparelho."
                    }
                } else {
                    val executor =
                        ContextCompat.getMainExecutor(activity)

                    val prompt = BiometricPrompt(
                        activity,
                        executor,
                        object : BiometricPrompt.AuthenticationCallback() {

                            override fun onAuthenticationSucceeded(
                                result: BiometricPrompt.AuthenticationResult
                            ) {
                                super.onAuthenticationSucceeded(result)
                                biometriaLiberada = true
                                autenticacaoIniciada = false
                                mensagem = ""
                            }

                            override fun onAuthenticationFailed() {
                                super.onAuthenticationFailed()
                                mensagem = "Digital não reconhecida. Tente novamente."
                            }

                            override fun onAuthenticationError(
                                errorCode: Int,
                                errString: CharSequence
                            ) {
                                super.onAuthenticationError(
                                    errorCode,
                                    errString
                                )
                                autenticacaoIniciada = false
                                mensagem = errString.toString()
                            }
                        }
                    )

                    val construtor =
                        BiometricPrompt.PromptInfo.Builder()
                            .setTitle("Brasileirão Predictor")
                            .setSubtitle("Confirme sua identidade para continuar")
                            .setDescription(
                                "Use sua digital ou a credencial de bloqueio do celular."
                            )
                            .setAllowedAuthenticators(autenticadores)

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        construtor.setNegativeButtonText("Cancelar")
                    }

                    prompt.authenticate(construtor.build())
                }
            }
        }
    }

    LaunchedEffect(auth.currentUser?.uid) {
        val usuario = auth.currentUser

        if (usuario != null) {
            carregando = true

            db.collection("users")
                .document(usuario.uid)
                .get()
                .addOnSuccessListener { documento ->
                    nomeUsuario = documento.getString("name")
                        ?: usuario.email?.substringBefore("@")
                        ?: "Torcedor"

                    time = documento.getString("favoriteTeam") ?: ""
                    usuarioLogado = true
                    carregando = false
                }
                .addOnFailureListener {
                    mensagem = "Não foi possível carregar seu perfil."
                    usuarioLogado = false
                    carregando = false
                }
        }
    }

    LaunchedEffect(usuarioLogado, auth.currentUser?.uid) {
        if (
            usuarioLogado &&
            auth.currentUser != null &&
            !biometriaLiberada &&
            !autenticacaoIniciada
        ) {
            iniciarBiometria()
        }
    }

    MaterialTheme {
        if (usuarioLogado && biometriaLiberada) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(fundo)
            ) {
                TelaPrincipal(
                    nome = nomeUsuario,
                    time = time
                )
            }
        } else if (usuarioLogado) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(fundo)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "BRASILEIRÃO PREDICTOR",
                    color = Color.White,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(35.dp))

                Text(
                    "ACESSO PROTEGIDO",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(15.dp))

                Text(
                    "Confirme sua identidade para acessar o aplicativo.",
                    color = Color.White,
                    fontSize = 16.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                if (mensagem.isNotEmpty()) {
                    Text(
                        mensagem,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(15.dp))
                }

                Button(
                    onClick = iniciarBiometria,
                    enabled = !autenticacaoIniciada &&
                        !biometriaLiberada
                ) {
                    Text("TENTAR NOVAMENTE")
                }

                TextButton(
                    onClick = {
                        auth.signOut()
                        usuarioLogado = false
                        biometriaLiberada = false
                        autenticacaoIniciada = false
                        cadastro = false
                        mensagem = ""
                    }
                ) {
                    Text(
                        "SAIR DA CONTA",
                        color = Color.White
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(fundo)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(35.dp))

                Text(
                    "BRASILEIRÃO",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "PREDICTOR 2026",
                    color = Color.White,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(25.dp))

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            if (cadastro) "CRIAR CONTA" else "ENTRAR",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(15.dp))

                        if (cadastro) {
                            OutlinedTextField(
                                value = nome,
                                onValueChange = { nome = it },
                                label = { Text("Seu nome") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        Box {
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text("E-mail") },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    if (emailsSalvos.isNotEmpty()) {
                                        TextButton(
                                            onClick = {
                                                menuEmailsAberto = true
                                            }
                                        ) {
                                            Text("▼")
                                        }
                                    }
                                }
                            )

                            DropdownMenu(
                                expanded = menuEmailsAberto,
                                onDismissRequest = {
                                    menuEmailsAberto = false
                                }
                            ) {
                                emailsSalvos.forEach { emailSalvo ->
                                    DropdownMenuItem(
                                        text = { Text(emailSalvo) },
                                        onClick = {
                                            email = emailSalvo
                                            menuEmailsAberto = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = senha,
                            onValueChange = { senha = it },
                            label = { Text("Senha") },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (senhaVisivel)
                                VisualTransformation.None
                            else
                                PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(
                                    onClick = {
                                        senhaVisivel = !senhaVisivel
                                    }
                                ) {
                                    Text(
                                        if (senhaVisivel) "Ocultar"
                                        else "Mostrar"
                                    )
                                }
                            }
                        )

                        if (cadastro) {
                            Spacer(modifier = Modifier.height(15.dp))

                            Text(
                                "ESCOLHA SEU TIME DO CORAÇÃO",
                                fontWeight = FontWeight.Bold
                            )

                            Box {
                                OutlinedButton(
                                    onClick = { menuAberto = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (time.isEmpty())
                                            "Selecionar time"
                                        else time
                                    )
                                }

                                DropdownMenu(
                                    expanded = menuAberto,
                                    onDismissRequest = {
                                        menuAberto = false
                                    }
                                ) {
                                    times.forEach { clube ->
                                        DropdownMenuItem(
                                            text = { Text(clube) },
                                            onClick = {
                                                time = clube
                                                menuAberto = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                if (
                                    email.isBlank() ||
                                    senha.isBlank() ||
                                    (cadastro && (
                                        nome.isBlank() || time.isBlank()
                                    ))
                                ) {
                                    mensagem = "Preencha todos os campos."
                                } else {
                                    carregando = true
                                    mensagem = ""

                                    if (cadastro) {
                                        auth.createUserWithEmailAndPassword(
                                            email.trim(),
                                            senha
                                        ).addOnCompleteListener { tarefa ->
                                            if (tarefa.isSuccessful) {
                                                val usuario = auth.currentUser!!

                                                val perfil = hashMapOf(
                                                    "name" to nome.trim(),
                                                    "email" to email.trim(),
                                                    "favoriteTeam" to time
                                                )

                                                db.collection("users")
                                                    .document(usuario.uid)
                                                    .set(perfil)
                                                    .addOnSuccessListener {
                                                        salvarEmail(email.trim())
                                                        nomeUsuario = nome.trim()
                                                        usuarioLogado = true
                                                        mensagem = ""
                                                        carregando = false
                                                    }
                                                    .addOnFailureListener { erro ->
                                                        mensagem =
                                                            "Erro ao salvar perfil: ${erro.message}"
                                                        carregando = false
                                                    }
                                            } else {
                                                mensagem =
                                                    tarefa.exception?.message
                                                        ?: "Erro ao criar conta."
                                                carregando = false
                                            }
                                        }
                                    } else {
                                        auth.signInWithEmailAndPassword(
                                            email.trim(),
                                            senha
                                        ).addOnCompleteListener { tarefa ->
                                            if (tarefa.isSuccessful) {
                                                salvarEmail(email.trim())
                                                val usuario = auth.currentUser!!

                                                db.collection("users")
                                                    .document(usuario.uid)
                                                    .get()
                                                    .addOnSuccessListener { documento ->
                                                        nomeUsuario =
                                                            documento.getString("name")
                                                                ?: email.substringBefore("@")

                                                        time =
                                                            documento.getString("favoriteTeam")
                                                                ?: ""

                                                        usuarioLogado = true
                                                        mensagem = ""
                                                        carregando = false
                                                    }
                                                    .addOnFailureListener { erro ->
                                                        mensagem =
                                                            "Erro ao carregar perfil: ${erro.message}"
                                                        carregando = false
                                                    }
                                            } else {
                                                mensagem =
                                                    tarefa.exception?.message
                                                        ?: "Erro ao entrar."
                                                carregando = false
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !carregando
                        ) {
                            Text(
                                if (carregando) "AGUARDE..."
                                else if (cadastro) "CRIAR MINHA CONTA"
                                else "ENTRAR"
                            )
                        }

                        TextButton(
                            onClick = {
                                cadastro = !cadastro
                                mensagem = ""
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text(
                                if (cadastro)
                                    "Já tenho conta. Entrar"
                                else
                                    "Não tenho conta. Cadastrar"
                            )
                        }

                        if (mensagem.isNotEmpty()) {
                            Text(
                                mensagem,
                                color = Color.DarkGray,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(25.dp))

                Text(
                    "ESTATÍSTICA • INTELIGÊNCIA • FUTEBOL",
                    color = Color.White,
                    fontSize = 11.sp
                )
            }
        }
    }
}


   
@Composable
fun TelaPrincipal(nome: String, time: String) {
    var abaSelecionada by remember { mutableStateOf("Início") }
    var carregandoJogos by remember { mutableStateOf(false) }
    var erroJogos by remember { mutableStateOf("") }
    var rodadaSelecionada by remember { mutableStateOf<Int?>(null) }
    var menuRodadasAberto by remember { mutableStateOf(false) }

    val jogos = remember {
        mutableStateListOf<org.json.JSONObject>()
    }

    val corFundo = when (time) {
        "Flamengo", "Vasco", "Corinthians", "Santos" -> Color(0xFF101010)
        "Palmeiras" -> Color(0xFF071C12)
        "São Paulo", "Fluminense" -> Color(0xFF19090C)
        "Grêmio", "Cruzeiro", "Bahia" -> Color(0xFF081629)
        "Internacional" -> Color(0xFF1B0808)
        else -> Color(0xFF101820)
    }

    val corCartao = when (time) {
        "Flamengo" -> Color(0xFF281017)
        "Palmeiras" -> Color(0xFF123D29)
        "Corinthians", "Vasco", "Santos", "Botafogo" -> Color(0xFF292929)
        "São Paulo" -> Color(0xFF42121A)
        "Grêmio", "Cruzeiro" -> Color(0xFF15385C)
        "Internacional" -> Color(0xFF491515)
        "Fluminense" -> Color(0xFF4A1425)
        "Bahia" -> Color(0xFF163D65)
        else -> Color(0xFF263746)
    }

    val corDestaque = when (time) {
        "Flamengo", "Internacional", "São Paulo" -> Color(0xFFFF354D)
        "Palmeiras" -> Color(0xFF4ADE80)
        "Corinthians", "Vasco", "Santos" -> Color(0xFFFFD54F)
        "Grêmio", "Cruzeiro", "Bahia" -> Color(0xFF55B8FF)
        "Fluminense" -> Color(0xFFE65B83)
        else -> Color(0xFFFFD54F)
    }

    val abas = listOf("Início", "Jogos", "Tabela", "Palpites")

    fun formatarDataBrasil(dataUtc: String): String {
        return try {
            if (dataUtc.length < 19) {
                return "Data a confirmar"
            }

            val formatoEntrada = java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss",
                java.util.Locale.US
            ).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }

            val dataConvertida = formatoEntrada.parse(dataUtc.substring(0, 19))
                ?: return "Data a confirmar"

            val formatoSaida = java.text.SimpleDateFormat(
                "dd/MM/yyyy • HH:mm",
                java.util.Locale("pt", "BR")
            ).apply {
                timeZone = java.util.TimeZone.getTimeZone("America/Sao_Paulo")
            }

            formatoSaida.format(dataConvertida)
        } catch (e: Exception) {
            "Data a confirmar"
        }
    }

    fun carregarJogos() {
        carregandoJogos = true
        erroJogos = ""

        DadosBrasileirao.buscarJogos { dados, erro ->
            carregandoJogos = false

            if (dados != null) {
                val lista = dados.optJSONArray("jogos")
                jogos.clear()

                if (lista != null) {
                    for (i in 0 until lista.length()) {
                        val jogo = lista.optJSONObject(i)
                        if (jogo != null) {
                            jogos.add(jogo)
                        }
                    }
                }

                if (jogos.isEmpty()) {
                    erroJogos = "Nenhum jogo foi encontrado."
                } else {
                    val proximoJogo = jogos.firstOrNull { jogo ->
                        val situacao = jogo.optString("situacao").uppercase()
                        situacao != "FINISHED" &&
                        situacao != "FINALIZADO" &&
                        situacao != "AWARDED"
                    }

                    rodadaSelecionada =
                        proximoJogo?.optString("rodada")?.toIntOrNull()
                            ?: jogos.firstOrNull()
                                ?.optString("rodada")
                                ?.toIntOrNull()
                }
            } else {
                erroJogos = erro ?: "Não foi possível carregar os jogos."
            }
        }
    }

    val rodadasDisponiveis = jogos
        .mapNotNull { it.optString("rodada").toIntOrNull() }
        .distinct()
        .sorted()

    val jogosDaRodada = jogos.filter {
        it.optString("rodada").toIntOrNull() == rodadaSelecionada
    }

    LaunchedEffect(abaSelecionada) {
        if (
            abaSelecionada == "Jogos" &&
            jogos.isEmpty() &&
            !carregandoJogos
        ) {
            carregarJogos()
        }
    }

    Column(
    modifier = Modifier
        .fillMaxSize()
        .background(corFundo)
        .navigationBarsPadding()
) {
    
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            Text(
                "BRASILEIRÃO",
                color = Color.White,
                fontSize = 27.sp,
                fontWeight = FontWeight.Black
            )

            Text(
                "PREDICTOR 2026 • BY NISCH",
                color = corDestaque,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(22.dp))

            when (abaSelecionada) {
                "Início" -> {
                    Text(
                        "Olá, $nome! 👋",
                        color = Color.White,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(15.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = corCartao
                        )
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                "SEU TIME DO CORAÇÃO",
                                color = Color.LightGray
                            )
                            Text(
                                "⚽ $time",
                                color = corDestaque,
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(25.dp))

                    Text(
                        "CENTRAL DO CAMPEONATO",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    listOf(
                        "⚽ Jogos da rodada" to "Jogos",
                        "🏆 Classificação" to "Tabela",
                        "🎯 Palpites inteligentes" to "Palpites"
                    ).forEach { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = corCartao
                            ),
                            onClick = { abaSelecionada = item.second }
                        ) {
                            Text(
                                item.first,
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(20.dp)
                            )
                        }
                    }
                }

                "Jogos" -> {
                    Text(
                        "⚽ JOGOS DO BRASILEIRÃO 2026",
                        color = corDestaque,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "Calendário oficial • Horário de Brasília",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(15.dp))

                    if (carregandoJogos) {
                        CircularProgressIndicator(color = corDestaque)
                        Text(
                            "Buscando partidas...",
                            color = Color.White,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }

                    if (erroJogos.isNotEmpty()) {
                        Text(
                            erroJogos,
                            color = Color.White,
                            fontSize = 14.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(onClick = { carregarJogos() }) {
                            Text("TENTAR NOVAMENTE")
                        }
                    }

                    if (jogos.isNotEmpty()) {
                        Text(
                            "${jogos.size} partidas no campeonato",
                            color = corDestaque,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Box {
                            OutlinedButton(
                                onClick = {
                                    menuRodadasAberto = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (rodadaSelecionada != null)
                                        "⚽  RODADA $rodadaSelecionada  ▼"
                                    else
                                        "SELECIONAR RODADA"
                                )
                            }

                            DropdownMenu(
                                expanded = menuRodadasAberto,
                                onDismissRequest = {
                                    menuRodadasAberto = false
                                }
                            ) {
                                rodadasDisponiveis.forEach { rodada ->
                                    DropdownMenuItem(
                                        text = {
                                            Text("Rodada $rodada")
                                        },
                                        onClick = {
                                            rodadaSelecionada = rodada
                                            menuRodadasAberto = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            "${jogosDaRodada.size} jogos nesta rodada",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )

                        jogosDaRodada.forEach { jogo ->
                            val mandante = jogo.optJSONObject("mandante")
                            val visitante = jogo.optJSONObject("visitante")
                            val placar = jogo.optJSONObject("placar")

                            val nomeMandante =
                                mandante?.optString("nome", "Mandante")
                                    ?: "Mandante"

                            val nomeVisitante =
                                visitante?.optString("nome", "Visitante")
                                    ?: "Visitante"

                            val golsMandante =
                                placar?.optInt("mandante", -1) ?: -1

                            val golsVisitante =
                                placar?.optInt("visitante", -1) ?: -1

                            val data = jogo.optString("data", "")
                            val dataExibida = formatarDataBrasil(data)

                            val situacao =
                                jogo.optString("situacao", "A confirmar")

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
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
                                        "RODADA $rodadaSelecionada",
                                        color = corDestaque,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Text(
                                        dataExibida,
                                        color = Color.LightGray,
                                        fontSize = 11.sp
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            nomeMandante,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f)
                                        )

                                        Text(
                                            if (golsMandante >= 0 && golsVisitante >= 0)
                                                "$golsMandante x $golsVisitante"
                                            else
                                                " x ",
                                            color = corDestaque,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Black
                                        )

                                        Text(
                                            nomeVisitante,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        situacao,
                                        color = Color.LightGray,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                "Tabela" -> {
    TelaTabela(
        corDestaque = corDestaque,
        corCartao = corCartao
    )
}
                "Palpites" -> {
    TelaPalpites(
        corDestaque = corDestaque,
        corCartao = corCartao
    )
 }
        
               
