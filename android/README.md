# PetGuard AI Android

Aplicativo Android nativo em Kotlin, Jetpack Compose e Material 3.

## Organização

- `data`: modelos, validação de entradas e contrato `PetGuardRepository`.
- `ui/viewmodel`: estado da aplicação, validações de fluxo e ações de tela.
- `ui/screens`: login, início, câmeras, linha do tempo e perfil.
- `ui/components`: componentes compartilhados.
- `ui/theme`: temas escuro, claro e paleta acessível para daltonismo.

## Integrações

O ViewModel depende de `PetGuardRepository`, que reúne autenticação, preferências, perfil do pet, câmeras e eventos. O Firebase Auth identifica a conta; o Supabase guarda e sincroniza os dados dessa conta, sem chamadas de serviço dentro dos Composables.

As telas têm estados de carregamento, conteúdo, vazio e erro, além das ações de formulário e navegação correspondentes.

### Firebase Authentication

1. No Firebase Console, crie um projeto e registre um aplicativo Android com o pacote `com.petguard.ai`.
2. Baixe o arquivo `google-services.json` e coloque-o em `app/google-services.json`.
3. Em Authentication, habilite o provedor **E-mail/senha**.
4. Sincronize o Gradle e compile novamente.

O app já usa Firebase Auth para criar conta, entrar com e-mail/senha, restaurar uma sessão existente, recuperar senha e sair. O login Google usa Credential Manager e troca o ID token por uma credencial Firebase.

Para habilitar também o login Google:

1. Em **Authentication > Método de login**, habilite **Google** e selecione o e-mail de suporte do projeto.
2. Em **Configurações do projeto > Geral > Seus apps**, adicione o SHA-1 da assinatura usada para executar o app (a tarefa `signingReport` do Gradle mostra o SHA-1 de debug).
3. Baixe novamente o `google-services.json` e substitua `app/google-services.json` pelo arquivo atualizado, que inclui os dados OAuth.
4. Sincronize o Gradle.

Enquanto o arquivo de configuração não está presente, o plugin do Google Services não é aplicado e a tela informa que falta configurar o Firebase.

### Supabase: sincronização entre celulares

O app envia o token ID da sessão Firebase ao Data API do Supabase. O conteúdo da conta fica em `public.petguard_user_data`, associado ao UID Firebase e protegido por RLS. A tela atualiza os dados compartilhados aproximadamente a cada 12 segundos enquanto a sessão está aberta. Não use chave `service_role` ou credenciais do Firebase Admin no Android.

1. No Supabase, abra **Project Settings > API Keys** e copie a chave **Publishable** (ou a chave `anon` legada). Não copie a `secret`/`service_role`.
2. No arquivo `local.properties` na raiz do projeto, mantenha `sdk.dir` e acrescente somente:

   ```properties
   supabase.publishableKey=COLE_A_CHAVE_PUBLICAVEL_AQUI
   ```

3. No **SQL Editor** do projeto Supabase, execute o conteúdo de `supabase/migrations/202611110001_petguard_user_data.sql`. Ele cria a tabela de sincronização e uma política RLS em que cada usuário acessa somente a linha cujo UID é o `sub` do próprio JWT Firebase.
4. Confirme que a integração de autenticação de terceiros Firebase está habilitada no Supabase, com o Project ID correto, e que a conta Firebase usada no app possui a claim `role: authenticated`. Depois de alterar claims, saia e entre no app para renovar o token.
5. Sincronize o Gradle, compile e instale o mesmo APK nos dois celulares. Entre nos dois com a mesma conta Firebase.

O perfil do tutor, pets, câmera salva, preferências e eventos do YOLO são sincronizados nessa tabela. O vídeo continua vindo diretamente do notebook/câmera: ambos os celulares precisam alcançar o endereço de transmissão pela rede configurada.

Abra esta pasta no Android Studio e sincronize o Gradle. O projeto requer JDK 17 e Android SDK 35.
