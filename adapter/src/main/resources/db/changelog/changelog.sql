--liquibase formatted sql

-- changeset cokeman:19_05_2026_01_init_routes_schema
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE SCHEMA IF NOT EXISTS routes;

-- changeset cokeman:19_05_2026_02_route_draft
CREATE TABLE IF NOT EXISTS routes.route_draft (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    geometry public.geometry(LineStringZ, 4326) NOT NULL,
    profile VARCHAR(64) NOT NULL,
    distance_km DOUBLE PRECISION,
    duration_sec INTEGER,
    elevation_gain INTEGER,
    elevation_loss INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_route_draft_user_id ON routes.route_draft (user_id);
CREATE INDEX IF NOT EXISTS idx_route_draft_geometry ON routes.route_draft USING GIST (geometry);
CREATE UNIQUE INDEX IF NOT EXISTS idx_route_draft_user_name ON routes.route_draft (user_id, name);

-- changeset cokeman:22_05_2026_03_route_draft_groups
-- Grupowanie szkiców w „wyprawy" (dni jednej wyprawy = wspólne group_id) + waypointy do edycji wczytanego szkicu.
ALTER TABLE routes.route_draft ADD COLUMN IF NOT EXISTS group_id   UUID;
ALTER TABLE routes.route_draft ADD COLUMN IF NOT EXISTS group_name VARCHAR(255);
ALTER TABLE routes.route_draft ADD COLUMN IF NOT EXISTS day_number INTEGER;
ALTER TABLE routes.route_draft ADD COLUMN IF NOT EXISTS waypoints  TEXT;
CREATE INDEX IF NOT EXISTS idx_route_draft_group ON routes.route_draft (user_id, group_id);

-- changeset cokeman:25_05_2026_01_route_draft_geometry_encoded
-- Geometria szkicu jako ZAKODOWANY ślad 3D (Polyline3DCodec: lng,lat,z) zamiast LINESTRING Z — kompaktowy zapis,
-- koniec z PostGIS dla draftów. Stare rekordy (geometria 2D / z=0) czyścimy — brak wstecznej kompatybilności.
DROP INDEX IF EXISTS routes.idx_route_draft_geometry;
DELETE FROM routes.route_draft;
ALTER TABLE routes.route_draft DROP COLUMN geometry;
ALTER TABLE routes.route_draft ADD COLUMN geometry TEXT NOT NULL;

-- changeset cokeman:26_05_2026_01_planning_schema
-- Schemat asystenta tras (przeniesione z assistant-service). JEDNA sesja na user — UNIQUE na user_id.
-- preferences = JSON formularza (intent-specific pola). Dni w osobnej tabeli (CASCADE DELETE).
CREATE SCHEMA IF NOT EXISTS planning;

CREATE TABLE IF NOT EXISTS planning.session (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL UNIQUE,
    intent       VARCHAR(32),
    preferences  TEXT NOT NULL DEFAULT '{}',
    last_task_id UUID,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS planning.session_day (
    id              UUID PRIMARY KEY,
    session_id      UUID NOT NULL REFERENCES planning.session(id) ON DELETE CASCADE,
    day_number      INTEGER NOT NULL,
    geometry        TEXT NOT NULL,
    waypoints       TEXT NOT NULL,
    distance_km     DOUBLE PRECISION,
    duration_sec    INTEGER,
    elevation_gain  INTEGER,
    elevation_loss  INTEGER,
    profile         VARCHAR(64) NOT NULL,
    edited_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (session_id, day_number)
);

CREATE INDEX IF NOT EXISTS idx_planning_session_day_session ON planning.session_day (session_id);

-- changeset cokeman:26_05_2026_02_planning_plan_task
-- Task obliczania trasy. Custom tracking (zamiast common-application/async TaskStatusService),
-- bez globalnego slotu — każdy user może mieć własny task RUNNING. Persistuje stan między widokami
-- (user wychodzi z mapy, wraca — task wciąż liczy w tle albo gotowe).
CREATE TABLE IF NOT EXISTS planning.plan_task (
    id                UUID PRIMARY KEY,
    session_id        UUID NOT NULL REFERENCES planning.session(id) ON DELETE CASCADE,
    user_id           UUID NOT NULL,
    status            VARCHAR(32) NOT NULL,
    phase             VARCHAR(64),
    progress_current  INTEGER,
    progress_total    INTEGER,
    error             TEXT,
    started_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at      TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_plan_task_user ON planning.plan_task (user_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_plan_task_session ON planning.plan_task (session_id);

-- changeset cokeman:26_05_2026_03_planning_summary
-- Podsumowanie policzonej wyprawy (totalKm/elev/duration/budget/verdict) — null jeśli sesja
-- jeszcze nie policzona. Płaskie kolumny zamiast JSONB (Hibernate validate przyjazne).
ALTER TABLE planning.session ADD COLUMN IF NOT EXISTS summary_total_distance_km DOUBLE PRECISION;
ALTER TABLE planning.session ADD COLUMN IF NOT EXISTS summary_total_elevation_gain INTEGER;
ALTER TABLE planning.session ADD COLUMN IF NOT EXISTS summary_total_duration_sec INTEGER;
ALTER TABLE planning.session ADD COLUMN IF NOT EXISTS summary_budget_km INTEGER;
ALTER TABLE planning.session ADD COLUMN IF NOT EXISTS summary_verdict VARCHAR(16);
ALTER TABLE planning.session ADD COLUMN IF NOT EXISTS summary_surplus_km INTEGER;
ALTER TABLE planning.session ADD COLUMN IF NOT EXISTS summary_pool_size INTEGER;
ALTER TABLE planning.session ADD COLUMN IF NOT EXISTS summary_initial_pool_size INTEGER;
ALTER TABLE planning.session ADD COLUMN IF NOT EXISTS summary_reconcile_iters INTEGER;
ALTER TABLE planning.session ADD COLUMN IF NOT EXISTS summary_reconcile_trims INTEGER;
ALTER TABLE planning.session ADD COLUMN IF NOT EXISTS summary_reconcile_grows INTEGER;

-- changeset cokeman:26_05_2026_04_planning_summary_baseline
-- Pola dodane w Fazie 3: baseline (start→via→meta bez gmin), dynamiczna kalibracja road_factor
-- (anchors vs areas), warning gdy realna trasa ma więcej wzniosu niż refClimbTotal × 1.10.
ALTER TABLE planning.session ADD COLUMN IF NOT EXISTS summary_baseline_km DOUBLE PRECISION;
ALTER TABLE planning.session ADD COLUMN IF NOT EXISTS summary_road_anchors DOUBLE PRECISION;
ALTER TABLE planning.session ADD COLUMN IF NOT EXISTS summary_road_areas DOUBLE PRECISION;
ALTER TABLE planning.session ADD COLUMN IF NOT EXISTS summary_climb_warning BOOLEAN;

-- changeset cokeman:29_05_2026_01_drop_duration_sec
-- Usunięcie modelu czasu: operujemy na dniach/km/wzniosie dziennym, czas (durationSec/total-time
-- z BRoutera) był zbędny — nie znamy prędkości usera. Drop kolumn we wszystkich tabelach.
ALTER TABLE routes.route_draft DROP COLUMN IF EXISTS duration_sec;
ALTER TABLE planning.session_day DROP COLUMN IF EXISTS duration_sec;
ALTER TABLE planning.session DROP COLUMN IF EXISTS summary_total_duration_sec;

-- changeset cokeman:09_06_2026_01_route_draft_stats
-- Persistencja statystyk surface/road/smoothness per day-draft (kompletny RouteStats z BRoutera)
-- jako JSON. Pozwala FE pokazać kolorowanie linii (Typy nawierzchni / Typy dróg) dla scalonej
-- wyprawy multi-day po reload'zie, a nie tylko świeżo po wyliczeniu. JSON zamiast osobnych tabel,
-- bo to read-mostly snapshot — czytamy razem z draftem, nie agregujemy w SQL.
-- Nullable: stare drafts (sprzed feature) nie mają stats; FE musi to obsłużyć (panel ukryty).
ALTER TABLE routes.route_draft ADD COLUMN IF NOT EXISTS stats_json TEXT;

-- changeset cokeman:09_06_2026_02_session_day_stats
-- Snapshot RouteStats per dzień (slice z full-route przez RouteStatsSlicer) — pozwala FE pokazać
-- panel "Typy nawierzchni / dróg" dla wyprawy asystenta (multi-day plan) bez ponownego BRouter call.
ALTER TABLE planning.session_day ADD COLUMN IF NOT EXISTS stats_json TEXT;

-- changeset cokeman:13_06_2026_01_session_day_covered_areas
-- v3.18: ID gmin ZALICZONYCH przez dzień (kryterium kredytu ≥200m, liczone backendem JTS) jako JSON
-- array — źródło prawdy dla kolorowania na froncie (zamiast re-derywacji turfem plain-touch).
-- Nullable: stare/ręczne dni bez danych backendowych → front liczy turfem (fallback).
ALTER TABLE planning.session_day ADD COLUMN IF NOT EXISTS covered_area_ids TEXT;
