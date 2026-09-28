# Kollus Spring Boot Sample — 플레이어 재생·콘텐츠 업로드·콜백·멀티 DRM 연동

## 개요

Kollus 미디어 플랫폼을 Spring Boot(Java 17) 환경에서 연동하는 서버/웹 샘플입니다.
플레이어 JWT 발급과 iframe/모바일 앱 재생, Upload API 를 통한 콘텐츠 업로드, Kollus 플레이어가 호출하는 콜백(재생·DRM·다음 회차·LMS) 수신,
그리고 Widevine/PlayReady/FairPlay 멀티 DRM(inkaDRM·Pallycon) 재생까지 하나의 서버에서 시연합니다. 외부 JWT 라이브러리 없이 HS256 서명을 직접 구현하고, 브라우저에서 직접 호출할 수 없는 Kollus API 는 서버가 프록시합니다.

홈(`/`)에는 Kollus 가 제공하는 공개 데모 콘텐츠 iframe(`https://v.kr.kollus.com/dzgAE3b9`)이 삽입되어 있어
키 설정 없이도 플레이어 동작을 바로 확인할 수 있으며, 아래 각 샘플 페이지로 이동하는 링크를 제공합니다.

### 샘플 플레이어 `/sample`

Secret Key, Custom Key, MCK를 입력해 서버(`POST /jwt/create`)에서 JWT를 발급받고 플레이어를 호출합니다.

- 웹 플레이어 (iframe) — 응답의 `link`(`https://v.kr.kollus.com/s?jwt=...&custom_key=...`)를 iframe `src` 로 지정
- 모바일 전용 플레이어 — SI 링크(`/si?jwt=...`)를 Kollus 앱 커스텀 스킴(`kollus://`, Android Chrome 은 `intent://`)으로 호출, 앱 미설치 시 스토어 이동
- 발급된 JWT 토큰 문자열 표시

> **주의**: `/sample` 페이지는 Secret Key 를 클라이언트에 노출하는 샘플/데모용입니다.
> 실제 서비스에서는 서버 사이드 발급(`/jwt/create`)만 사용하세요.

### 멀티 DRM 플레이어 `/multidrm`

Widevine / PlayReady / FairPlay 멀티 DRM(inkaDRM·Pallycon) 재생 샘플입니다.
MCK, Content ID(멀티 DRM 콘텐츠 ID / 업로드 파일 키), CUID(선택)를 입력하면 서버 플레이어 라우트(`GET /player/multi-drm`)를 iframe 으로 로드합니다.

- 브라우저 User-Agent 에 맞는 DRM 종류/스트리밍 방식(Widevine·dash / PlayReady·dash / FairPlay·hls)을 서버가 자동 선택
- inkaDRM 라이선스 룰을 AES-256-CBC 로 암호화하고 SHA-256 해시를 계산해 `pallycon-customdata-v2` 커스텀 헤더 페이로드 조립
- 일반 재생과 **다른 멀티 DRM 전용 Kollus 계정 키**(`kollus.multidrm.*`)로 JWT 서명
- 설정 누락 시 누락 항목을 안내하는 에러 페이지(500) 렌더링

### 파일 업로드 `/upload`

Kollus Upload API를 통해 파일을 업로드합니다.

- 업로드 URL 발급 (`POST /api/upload/create-url`) — 서버가 Kollus API 를 프록시
  - 일반 업로드: `c-api-kr.kollus.com` / 응답 `{status, data}`
  - 패스스루 업로드(인코딩 없이 원본 그대로 저장): `upload.kr.kollus.com` / 응답 `{error, message, result}` / `profile_key` 필수
- 발급된 `upload_url` 로 실제 `<form>` 을 hidden iframe 대상으로 제출해 파일 전송 (fetch/XHR 은 CORS preflight 에 막혀 사용하지 않음)
- 응답의 `upload_file_key` 를 화면에 표시하고, `progress_url` 을 5초 간격으로 폴링해 업로드 진행률(%)을 표시 — 100% 도달 시 완료 처리
- 옵션: 제목(미입력 시 파일명), 카테고리 키, 업로드 URL 유효 시간(60~21600초, 기본 600), 암호화 여부, 오디오 전용 여부

### JWT 발급 API

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET  | `/jwt/create` | 쿼리 파라미터로 JWT 발급 |
| POST | `/jwt/create` | JSON 바디로 JWT 발급 |

- 필수 파라미터: `mck` (미디어 콘텐츠 키)
- `secretKey`, `customKey` 미지정 시 서버 설정값(`kollus.secret-key` / `kollus.custom-key`) 사용
- 선택 파라미터: `cuid`, `expt`(분, 기본 3), `seek`, `seekable_end`, `intro`, `outro`, `play_section`, `mcpf`, `title`,
  `playback_rates`, `subtitle_policy`, `mc_subtitle_policy`, `next_episode`(기본 true), `playcallback_ignore`, `video_watermarking_code_policy`
- 응답: `{ token, link, payload }`

### 콜백 처리

Kollus 플레이어에서 발생하는 콜백을 수신합니다.

| 경로 | 설명 | 응답 |
|---|---|---|
| `POST /callback/play`         | 재생 콜백 (kind1: 재생 만료 설정, kind3: 최종 재생 승인)      | JWT (text/plain) + `X-KOLLUS-USERKEY` 헤더 |
| `POST /callback/drm`          | DRM 콜백 (kind1: 다운로드 승인, kind2: 다운로드 완료, kind3: 오프라인 재생 권한) | JWT (text/plain) + `X-KOLLUS-USERKEY` 헤더 |
| `POST /callback/next-episode` | 다음 회차 연속 재생 — `next_url` 은 SI 링크(`/si`) 형태       | JSON `{ result, next_url }` |
| `POST /callback/lms`          | 시청 이력 및 학습 진도 수신 (진도율·구간 이동 감지 요약)         | 200 OK (본문 없음) |

LMS 콜백 요약 데이터는 `logs/lms.log` 에 한 줄 JSON 으로 기록됩니다. (`logs/` 는 `.gitignore` 대상)

## 사전 요구사항

- JDK 17 이상 (Microsoft OpenJDK 17 기준) — Gradle 툴체인이 Java 17 을 요구합니다
- Kollus 서비스 계정 및 API Key (Kollus 관리자 콘솔 로그인 필요)
- (멀티 DRM 사용 시, 선택) 멀티 DRM 전용 Kollus 서비스 계정 + DoveRunner(Pallycon/inkaDRM) 계정
- Gradle 은 별도 설치가 필요 없습니다 (`gradlew` 래퍼 포함)

## 환경 설정

`application-local.yaml.example` 를 복사해서 시작합니다.

```bash
cp src/main/resources/application-local.yaml.example src/main/resources/application-local.yaml
```

`src/main/resources/application-local.yaml` 에서 아래 값을 실제 값으로 교체합니다.
(이 파일은 `.gitignore` 에 포함되어 있어 커밋되지 않습니다. 공통 기본값은 `application.yaml` 에 있으며 `local` 프로파일이 기본 활성화됩니다.)

### 기본 키 (플레이어 재생 · 업로드 · 콜백)

| 키 (`kollus.*`) | 설명 | 발급 위치 |
|---|---|---|
| `secret-key`   | JWT 서명 키          | 서비스 계정 > 보안키 |
| `custom-key`   | 플레이어 URL 커스텀 키 | 서비스 계정 > 커스텀키 |
| `api-token`    | Upload API 액세스 토큰 | 서비스 계정 > API 토큰 |
| `category-key` | 업로드 카테고리 키     | 콘텐츠 > 카테고리 |
| `sample-mck`   | 샘플 플레이어 페이지에 기본 노출할 MCK (선택, 실제 서비스에서는 제거) | 콘텐츠 > 미디어 콘텐츠 키 |
| `next-episode-mcks` | 다음 회차 콜백 샘플 MCK 목록 (선택, 예: `["mckA", "mckB"]`) | 콘텐츠 > 미디어 콘텐츠 키 |

`next-episode-mcks` 에 샘플 MCK 목록을 넣으면 다음 회차 콜백이 동작합니다.

### 멀티 DRM 키 (선택 — `/multidrm` 재생 시에만 필요)

멀티 DRM 은 일반 재생과 **다른 별도 Kollus 계정** + Pallycon(inkaDRM) 계정 키를 사용합니다. 사용하지 않으면 비워둬도 됩니다.
누락 상태로 `/player/multi-drm` 을 호출하면 어떤 키가 빠졌는지 안내하는 에러 페이지가 표시됩니다.

| 키 (`kollus.*`) | 설명 | 발급 위치 / 기본값 |
|---|---|---|
| `multidrm.security-key` | 멀티 DRM 전용 JWT 서명 키 | 멀티 DRM 전용 서비스 계정 > 보안키 |
| `multidrm.custom-key`   | 멀티 DRM 전용 플레이어 URL 커스텀 키 | 멀티 DRM 전용 서비스 계정 > 커스텀키 |
| `inka.access-key`       | inkaDRM Access Key (커스텀 헤더 해시 계산용) | DoveRunner(Pallycon) 콘솔 > Access Key |
| `inka.site-key`         | 라이선스 룰 AES-256-CBC 암호화 키 (32바이트) | DoveRunner 콘솔 > Site Key |
| `inka.site-id`          | inkaDRM Site ID | DoveRunner 콘솔 > Site ID |
| `inka.iv`               | 라이선스 룰 암호화 IV (16바이트) | 기본값 `0123456789abcdef` |
| `inka.license-url`      | DoveRunner 라이선스 서버 URL | 기본값 `https://drm-license.doverunner.com/ri/licenseManager.do` |
| `inka.certificate-url`  | DoveRunner FairPlay 인증서 서버 URL (`?siteId=` 는 서버가 자동 부착) | 기본값 `https://drm-license.doverunner.com/ri/fpsKeyManager.do` |

## 실행 방법

```bash
# 개발 (Gradle)
./gradlew bootRun

# 빌드 후 실행
./gradlew build
java -jar build/libs/kollus-0.0.1-SNAPSHOT.jar
```

기본 포트: http://localhost:8080 (변경: `--server.port=8081` 등 Spring Boot 표준 옵션)

| 페이지 | 설명 |
|---|---|
| `/`         | 홈 — 공개 데모 콘텐츠 플레이어 + 샘플 페이지 링크 |
| `/sample`   | JWT 발급 + 웹/모바일 플레이어 호출 |
| `/multidrm` | 멀티 DRM 플레이어 (Widevine/PlayReady/FairPlay) |
| `/upload`   | Kollus Upload API 파일 업로드 |

> `gradlew` 에 실행 권한이 없는 환경에서는 `sh ./gradlew bootRun` 처럼 `sh` 로 실행하거나 `chmod +x gradlew` 후 실행하세요.

## 주요 파일

### Java 소스 (`src/main/java/com/example/kollus/`)

| 파일 | 설명 |
|---|---|
| `KollusApplication.java`               | Spring Boot 애플리케이션 진입점 |
| `config/KollusProperties.java`         | `kollus.*` 커스텀 프로퍼티 바인딩 (record). 중첩 `MultiDrm`(security-key/custom-key), `Inka`(access-key/site-key/site-id/iv/license-url/certificate-url) 포함 |
| `config/KollusApiConfig.java`          | Upload API(`c-api-kr.kollus.com`) / 패스스루 업로드 API(`upload.kr.kollus.com`) 용 `RestClient` 빈 |
| `config/KollusHeaderFilter.java`       | 플레이어 iframe 권한 위임(`Permissions-Policy`) 및 Private Network Access(PNA) 허용 헤더 필터 |
| `controller/PageController.java`       | `GET /`, `GET /sample`, `GET /upload` 페이지 렌더링 |
| `controller/MultiDrmController.java`   | `GET /multidrm` 입력 폼, `GET /player/multi-drm` 멀티 DRM 플레이어 URL 생성 후 iframe 플레이어 렌더링 |
| `controller/JwtController.java`        | `GET/POST /jwt/create` 플레이어 JWT 발급 |
| `controller/UploadApiController.java`  | `POST /api/upload/create-url` 업로드 URL 발급 프록시 (일반/패스스루 분기, 파라미터 검증) |
| `controller/CallbackController.java`   | `POST /callback/play`, `/drm`, `/next-episode`, `/lms` 콜백 처리 |
| `service/KollusTokenService.java`      | HS256 JWT 생성/서명, 플레이어 URL 조립, 멀티 DRM 전용 키 서명(`buildMultiDrmPlayerUrl`), 콜백 JWT 서명 |
| `service/KollusMultiDrmService.java`   | User-Agent → DRM 종류/스트리밍 방식 판별, inkaDRM 라이선스 룰 암호화·해시, `drm_policy` 조립 |
| `service/KollusUploadService.java`     | Kollus Upload API 프록시 (`createUploadUrl`, `createPassthroughUploadUrl`) |
| `dto/TokenResponse.java`               | JWT 발급 결과 (`token`, `link`, `payload`) |
| `exception/KollusApiException.java`    | Kollus API 호출 실패 예외 (원격 상태 코드 + 응답 본문 보존) |
| `exception/GlobalExceptionHandler.java`| API 컨트롤러 공통 예외 처리 (`{status: "fail", error}` JSON 응답) |

### 리소스 (`src/main/resources/`)

| 파일 | 설명 |
|---|---|
| `application.yaml`                | 공통 설정 (`local` 프로파일 활성화, 포트 8080, `kollus.*` 기본값) |
| `application-local.yaml.example`  | 민감정보 템플릿 (커밋됨) — 복사해 `application-local.yaml` 생성 |
| `templates/index.html`            | 홈 — 공개 데모 플레이어 + 샘플 페이지 링크 |
| `templates/sample.html`           | 샘플 플레이어 — JWT 발급(`handleJwtIssue`), iframe 재생, 모바일 앱 스킴 호출(`handlePlayerCall`) |
| `templates/multidrm.html`         | 멀티 DRM 입력 폼 — `/player/multi-drm` 을 iframe 으로 로드(`handlePlay`) |
| `templates/player.html`           | 멀티 DRM 플레이어 — 컨트롤러가 생성한 JWT 서명 URL 을 iframe 으로 렌더링 |
| `templates/upload.html`           | 업로드 — 업로드 URL 발급 후 hidden iframe 대상 form 제출(`handleUploadClick`), 진행률 폴링(`startProgressUrlPolling`) |
| `templates/error.html`            | 에러 페이지 (멀티 DRM 설정 누락 등) |
| `static/stylesheets/style.css`    | 공통 스타일 |

## API 흐름

```
클라이언트(브라우저)
  │
  ├── GET /sample
  │     └── POST /jwt/create → { token, link, payload } → link 를 iframe src 로 재생
  │           (모바일: /si 링크를 kollus:// 또는 intent:// 스킴으로 앱 호출)
  │
  ├── GET /multidrm
  │     └── iframe GET /player/multi-drm?media_content_key=&content_id=[&cuid=]
  │           ├── User-Agent → DRM 종류/스트리밍 방식 판별
  │           ├── inkaDRM 라이선스 룰 암호화 + 해시 → pallycon-customdata-v2 커스텀 헤더
  │           └── 멀티 DRM 전용 키로 JWT 서명 → 플레이어 URL → player.html (iframe)
  │
  ├── GET /upload
  │     ├── POST /api/upload/create-url → Kollus c-api-kr(일반) / upload.kr(패스스루) 프록시
  │     │     └── { upload_url, progress_url, upload_file_key }
  │     ├── <form> 제출 (target = hidden iframe) → upload_url (Kollus 업로드 서버)
  │     └── fetch progress_url 5초 간격 폴링 → 진행률(%) 갱신, 100% 시 완료
  │
  └── Kollus 플레이어 → 서버 콜백
        ├── POST /callback/play
        ├── POST /callback/drm
        ├── POST /callback/next-episode
        └── POST /callback/lms
```

### 플레이어 재생 (JWT)

1. `/sample` 의 `handleJwtIssue()` 가 `POST /jwt/create` 에 `{ mck, cuid, expt, secretKey, customKey }` JSON 을 전송합니다.
2. `KollusTokenService.generatePlayerToken()` 이 페이로드(`cuid`, `expt`, `mc[]`, 전역 옵션)를 만들고 HS256 으로 서명해
   `https://v.kr.kollus.com/s?jwt=...&custom_key=...` 링크를 반환합니다.
3. 응답의 `link` 를 iframe `src` 로 지정해 웹 플레이어를 재생합니다.
4. 모바일에서는 `handlePlayerCall('path' | 'download')` 가 SI 링크(`/si?jwt=...&custom_key=...`)를 Kollus 앱 스킴으로 호출합니다.

### 멀티 DRM 재생

1. `/multidrm` 의 `handlePlay()` 가 `/player/multi-drm?media_content_key=…&content_id=…[&cuid=…]` 를 iframe `src` 로 지정합니다.
   서버가 요청 User-Agent 로 DRM 을 판별하므로 fetch 가 아닌 **브라우저가 직접 로드**하도록 iframe 을 사용합니다.
2. `MultiDrmController.player()` → `KollusMultiDrmService.buildPlayerUrl()`:
   - `kollus.multidrm.*` / `kollus.inka.*` 설정 검증 (누락 시 `IllegalStateException` → 500 `error.html`)
   - User-Agent 매핑: `CriOS`·`Safari` → FairPlay/hls, `Edge`·`Edg`·`Opera`·`MSIE`·`Trident` → PlayReady/dash,
     `Firefox`·`Chrome` → Widevine/dash (기본값), Mac 용 Edge 는 Chromium 엔진이므로 Widevine 으로 재분류
   - 고정 라이선스 룰(JSON)을 `site-key`/`iv` 로 AES-256-CBC 암호화 → base64 `token`
   - `hash = base64(SHA-256(accessKey + drmType + siteId + userId + cid + token + timestamp))`
   - `{ drm_type, site_id, user_id, cid, token, timestamp, hash }` 를 base64 JSON 으로 만들어 `custom_header` (`pallycon-customdata-v2`) 에 담고,
     `drm_policy { kind: "inka", streaming_type, data { license_url, certificate_url?siteId=…, custom_header } }` 를 `mc` 항목에 포함
   - `KollusTokenService.buildMultiDrmPlayerUrl()` 이 **멀티 DRM 전용** `security-key` 로 서명하고 `custom-key` 로 URL 조립
3. `player.html` 이 생성된 URL 을 iframe 으로 렌더링합니다.

### 콘텐츠 업로드

1. `/upload` 의 `handleUploadClick()` 이 `POST /api/upload/create-url` 로 업로드 URL 발급을 요청합니다.
   서버(`UploadApiController` → `KollusUploadService`)가 `access_token`/`category_key` 미지정 시 설정값으로 대체하고,
   `is_passthrough` 여부에 따라 일반(`c-api-kr.kollus.com/api/upload/create-url`) 또는 패스스루(`upload.kr.kollus.com/api/v1/create_url`) 엔드포인트를 호출합니다.
2. 응답에서 `upload_url`, `progress_url`, `upload_file_key` 를 꺼냅니다. (일반 `{status:"ok", data}` / 패스스루 `{error:0, result}`)
3. **파일 전송은 XHR/fetch 가 아니라 실제 `<form>` 제출**입니다. Kollus 업로드 서버는 실제 form 제출(브라우저 네비게이션)을 전제로
   설계되어 있어 fetch/XHR 로 `upload_url` 에 직접 요청하면 CORS preflight 에 막힙니다. 그래서 form 의 `action` 을 `upload_url` 로,
   `target` 을 hidden iframe 으로 바꿔 제출합니다.
4. form+iframe 제출은 cross-origin 이라 전송 결과를 JS 로 읽을 수 없으므로, `startProgressUrlPolling()` 이 `progress_url` 을
   5초 간격으로 폴링해 진행률을 표시하고 100% 도달 시 완료 처리합니다. 화면에는 `upload_file_key` 와 진행률(%)이 표시됩니다.

### 콜백

- `play`/`drm` 콜백은 페이로드를 `kollus.secret-key` 로 HS256 서명한 JWT 를 `text/plain` 본문으로, `kollus.custom-key` 를 `X-KOLLUS-USERKEY` 헤더로 응답합니다.
- `next-episode` 콜백은 `next-episode-mcks` 에서 다음 MCK 를 골라 JWT 를 발급하고 `/s?` 를 `/si?` 로 바꾼 SI 링크를 `next_url` 로 반환합니다.
- `lms` 콜백은 `json_data` 를 파싱해 진도율·구간 이동(트리키 클릭) 여부를 요약하고 `logs/lms.log` 에 기록한 뒤 항상 200 을 응답합니다.

## 관련 문서

- [Kollus API 레퍼런스](https://docs.kollus.com)
- [Kollus 관리자 콘솔](https://admin.kollus.com)
- `src/main/resources/application-local.yaml.example` — 설정 키 전체 목록과 발급 위치 주석
