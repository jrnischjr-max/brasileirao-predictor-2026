# Configuração do ranking automático 2026

O workflow `.github/workflows/ranking-diario.yml` executa diariamente às 09:00 UTC (06:00 em Brasília) e pode ser iniciado manualmente em Actions.

## 1. Credencial do Firebase no GitHub

O cálculo é executado fora do Android. Para ler os palpites e publicar somente o Top 10, o workflow precisa de uma credencial de servidor do Firebase.

1. No Google Cloud Console, abra o projeto `brasileirao-predictor-2026`.
2. Em IAM e administrador > Contas de serviço, crie uma conta dedicada, por exemplo `ranking-automation`.
3. Conceda a função mínima necessária para leitura e gravação no Firestore (Cloud Datastore User / `roles/datastore.user`).
4. Gere uma chave JSON para essa conta e baixe o arquivo.
5. No GitHub, abra Settings > Secrets and variables > Actions > New repository secret.
6. Nome do secret: `FIREBASE_SERVICE_ACCOUNT_JSON`.
7. Cole como valor o conteúdo completo do arquivo JSON e salve.

**Segurança:** não envie esse JSON pelo chat, não o coloque no repositório e não o inclua em prints. Ele é uma credencial privada de servidor. Se for exposto, revogue a chave imediatamente e gere outra.

## 2. Leitura do ranking pelo aplicativo

O workflow publica um único documento em `rankings/2026`. O documento contém somente posição, apelido, pontos, acertos, placares exatos, total de palpites avaliados e data da atualização. Não publica UID, e-mail nem os palpites individuais.

Nas regras atuais do Firestore, dentro do bloco `match /databases/{database}/documents`, permita a leitura autenticada do ranking e bloqueie gravação pelo aplicativo:

```text
match /rankings/{season} {
  allow read: if request.auth != null;
  allow write: if false;
}
```

Adicione esse bloco às regras existentes, sem substituir as regras das coleções `users` ou outras áreas do aplicativo.

## 3. Teste inicial

Depois de salvar o secret e publicar a regra de leitura:
1. Abra Actions > Atualizar ranking diário do Brasileirão.
2. Use Run workflow.
3. Confirme que a execução terminou em verde e que o documento `rankings/2026` foi criado no Firestore.

A rotina diária continuará agendada para 06:00 (horário de Brasília).
