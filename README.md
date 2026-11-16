# PetGuard AI

Projeto acadêmico para acompanhamento de pets, com aplicativo Android em Kotlin/Jetpack Compose, autenticação Firebase, sincronização Supabase e API de câmera/análise YOLO.

## Pastas

- `android/`: aplicativo, telas Compose, Firebase Auth, sincronização Supabase e SQL de configuração/RLS.
- `api-yolo/`: API FastAPI, scripts de inicialização para Windows e pesos YOLOv8n.
- `distribuicao/PetGuardAI-debug.apk`: APK de depuração para testes e demonstração em Android.

## Configurar o Android

1. Abra `android/` no Android Studio e sincronize o Gradle.
2. O arquivo `android/app/google-services.json` é a configuração cliente Firebase do projeto. Se necessário, baixe-o no Firebase Console para o pacote `com.petguard.ai`. Não use chave do Firebase Admin SDK no app.
3. Configure `android/local.properties` com o caminho do SDK, URL e chave pública do Supabase:

   ```properties
   sdk.dir=C\:\\Users\\SEU_USUARIO\\AppData\\Local\\Android\\Sdk
   supabase.url=https://SEU_PROJECT_REF.supabase.co
   supabase.anonKey=SUA_CHAVE_PUBLISHABLE_OU_ANON
   ```

   Use somente a chave pública `publishable`/`anon`; nunca use `service_role`/`secret` no APK. No GitHub Actions, configure os secrets `SUPABASE_URL` e `SUPABASE_ANON_KEY`.
4. Aplique em ordem as migrações SQL em `android/supabase/migrations/` no projeto Supabase antes de testar contas reais.

## Iniciar a API de câmera e YOLO (Windows)

1. Instale Python 3.10 ou superior.
2. Na pasta `api-yolo/`, execute `iniciar_api_local.bat`.
3. Conecte o celular e o notebook à mesma rede Wi-Fi e autorize a API no Firewall do Windows, se solicitado.
4. Entre no app com uma conta Firebase e use **Adicionar câmera** para localizar o notebook. O túnel Cloudflare é opcional para conexões fora da rede local.

A câmera e a análise rodam no computador que inicia a API. Mantenha-o ligado durante a demonstração.

## APK

O APK em `distribuicao/` é uma versão `debug`, voltada a testes e demonstração. Ele instala em Android, não em iPhone.
