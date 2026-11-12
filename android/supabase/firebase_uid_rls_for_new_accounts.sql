-- Execute uma vez no SQL Editor do Supabase PetGuardAI.
-- Firebase JWTs sem a claim `role` usam o papel Postgres `anon` no Supabase.
-- As políticas abaixo só permitem acesso quando a linha pertence ao UID Firebase
-- do token e o token veio do projeto Firebase PetGuard AI.

GRANT USAGE ON SCHEMA public TO anon;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.petguard_user_data TO anon;

DROP POLICY IF EXISTS petguard_user_data_firebase_uid_anon
    ON public.petguard_user_data;
CREATE POLICY petguard_user_data_firebase_uid_anon
    ON public.petguard_user_data
    FOR ALL
    TO anon
    USING (firebase_uid = (auth.jwt() ->> 'sub'))
    WITH CHECK (firebase_uid = (auth.jwt() ->> 'sub'));

DROP POLICY IF EXISTS petguard_user_data_firebase_project_anon
    ON public.petguard_user_data;
CREATE POLICY petguard_user_data_firebase_project_anon
    ON public.petguard_user_data
    AS RESTRICTIVE
    FOR ALL
    TO anon
    USING (
        auth.jwt() ->> 'iss' = 'https://securetoken.google.com/petguard-ai-133cd'
        AND auth.jwt() ->> 'aud' = 'petguard-ai-133cd'
    )
    WITH CHECK (
        auth.jwt() ->> 'iss' = 'https://securetoken.google.com/petguard-ai-133cd'
        AND auth.jwt() ->> 'aud' = 'petguard-ai-133cd'
    );
