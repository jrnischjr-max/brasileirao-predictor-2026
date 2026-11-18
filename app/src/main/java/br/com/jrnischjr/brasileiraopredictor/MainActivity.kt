package br.com.jrnischjr.brasileiraopredictor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
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
    var mensagem by remember { mutableStateOf("") }
    var carregando by remember { mutableStateOf(false) }

    val fundo = when (time) {
        "Flamengo" -> Color(0xFF8B0000)
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

    MaterialTheme {
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
                color = Color(0xFFFFD54F),
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(25.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        if (cadastro) "CRIAR CONTA"
                        else "ENTRAR",
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

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("E-mail") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
    value = senha,
    onValueChange = { senha = it },
    label = { Text("Senha") },
    modifier = Modifier.fillMaxWidth(),
    visualTransformation = if (senhaVisivel)
        androidx.compose.ui.text.input.VisualTransformation.None
    else
        androidx.compose.ui.text.input.PasswordVisualTransformation(),
    trailingIcon = {
        TextButton(
            onClick = { senhaVisivel = !senhaVisivel }
        ) {
            Text(if (senhaVisivel) "Ocultar" else "Mostrar")
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
                            if (email.isBlank() ||
                                senha.isBlank() ||
                                (cadastro && (nome.isBlank() || time.isBlank()))
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
                                                    mensagem = "Conta criada com sucesso!"
                                                    carregando = false
                                                }
                                                .addOnFailureListener { erro ->
                                                    mensagem = "Conta criada, mas houve erro ao salvar perfil: ${erro.message}"
                                                    carregando = false
                                                }
                                        } else {
                                            mensagem = tarefa.exception?.message
                                                ?: "Erro ao criar conta."
                                            carregando = false
                                        }
                                    }
                                } else {
                                    auth.signInWithEmailAndPassword(
                                        email.trim(),
                                        senha
                                    ).addOnCompleteListener { tarefa ->
                                        carregando = false
                                        mensagem = if (tarefa.isSuccessful) {
                                            "Login realizado com sucesso!"
                                        } else {
                                            tarefa.exception?.message
                                                ?: "Erro ao entrar."
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
