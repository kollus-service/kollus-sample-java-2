# Kollus 미디어 플랫폼 연동 샘플 코드 — Java / Spring Boot

Kollus 미디어 플랫폼을 Spring Boot 환경에서 연동하는 서버/웹 샘플입니다.

## 사전 요구사항

- JDK 17 이상 (Microsoft OpenJDK 17 기준)
- Kollus 서비스 계정 및 API Key (Kollus 관리자 콘솔 로그인 필요)

## 환경 설정

`application-local.yaml.example` 를 복사해서 시작합니다.

```bash
cp src/main/resources/application-local.yaml.example src/main/resources/application-local.yaml
```

`src/main/resources/application-local.yaml` 에서 아래 값을 실제 값으로 교체합니다.
(이 파일은 `.gitignore` 에 포함되어 있어 커밋되지 않습니다.)

| 키 (`kollus.*`) | 설명 | 위치 |
|---|---|---|
| `secret-key`   | JWT 서명 키          | 서비스 계정 > 보안키 |
| `custom-key`   | 플레이어 URL 커스텀 키 | 서비스 계정 > 커스텀키 |
| `api-token`    | Upload API 액세스 토큰 | 서비스 계정 > API 토큰 |
| `category-key` | 업로드 카테고리 키     | 콘텐츠 > 카테고리 |

`next-episode-mcks` 에 샘플 MCK 목록을 넣으면 다음 회차 콜백이 동작합니다.

## 실행

```bash
# 개발 (Gradle)
./gradlew bootRun

# 빌드 후 실행
./gradlew build
java -jar build/libs/kollus-0.0.1-SNAPSHOT.jar
```

기본 포트: http://localhost:8080

## 주요 기능

### 샘플 플레이어 `/sample`

Secret Key, Custom Key, MCK를 입력해 JWT를 발급하고 플레이어를 호출합니다.

- 웹 플레이어 (iframe)
- 모바일 전용 플레이어 (Kollus 앱 커스텀 스킴 `kollus://`)

### 파일 업로드 `/upload`

Kollus Upload API를 통해 파일을 업로드합니다.

- 업로드 URL 발급 (`POST /api/upload/create-url`)
- 발급된 URL로 파일 직접 전송
- Upload File Key 및 인코딩 상태 링크 확인

### JWT 발급 API

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET  | `/jwt/create` | 쿼리 파라미터로 JWT 발급 |
| POST | `/jwt/create` | JSON 바디로 JWT 발급 |

- 필수 파라미터: `mck` (미디어 콘텐츠 키)
- 선택 파라미터: `cuid`, `expt`, `seek`, `intro`, `outro`, `play_section`,
  `playback_rates`, `subtitle_policy`, `video_watermarking_code_policy` 등

### 콜백 처리

Kollus 플레이어에서 발생하는 콜백을 수신합니다.

| 경로 | 설명 | 응답 |
|---|---|---|
| `POST /callback/play`         | 재생 콜백 (kind1: 만료 설정, kind3: 재생 승인) | JWT |
| `POST /callback/drm`          | DRM 콜백 (다운로드 승인/완료/재생 권한)        | JWT |
| `POST /callback/next-episode` | 다음 회차 연속 재생                          | JSON |
| `POST /callback/lms`          | 시청 이력 및 학습 진도 수신                    | 200 OK |

LMS 콜백 데이터는 `logs/lms.log` 에 기록됩니다.

## API 흐름

```
클라이언트
  │
  ├── GET /sample
  │     └── POST /jwt/create → JWT + 플레이어 URL 반환
  │
  ├── GET /upload
  │     ├── POST /api/upload/create-url → Kollus c-api-kr 프록시
  │     └── XHR → Kollus 업로드 서버 (직접 전송)
  │
  └── Kollus 플레이어
        ├── POST /callback/play
        ├── POST /callback/drm
        ├── POST /callback/next-episode
        └── POST /callback/lms
```

## 프로젝트 구조

```
src/main/java/com/example/kollus/
├── KollusApplication.java            애플리케이션 진입점
├── config/
│   ├── KollusProperties.java         kollus.* 커스텀 프로퍼티 바인딩
│   ├── KollusApiConfig.java          Upload API 용 RestClient 빈
│   └── KollusHeaderFilter.java       PNA/iframe 권한 헤더 필터
├── controller/
│   ├── PageController.java           /, /sample, /upload 페이지
│   ├── JwtController.java            /jwt/create
│   ├── UploadApiController.java      /api/upload/*
│   └── CallbackController.java       /callback/*
├── service/
│   ├── KollusTokenService.java       JWT 생성/서명
│   └── KollusUploadService.java      Upload API 프록시
├── dto/
│   └── TokenResponse.java
└── exception/
    ├── KollusApiException.java
    └── GlobalExceptionHandler.java

src/main/resources/
├── application.yaml                  공통 설정 (profile local 활성화)
├── application-local.yaml.example    민감정보 템플릿 (커밋됨)
├── templates/                        Thymeleaf 뷰
└── static/stylesheets/style.css
```

> **주의**: `/sample` 페이지는 Secret Key 를 클라이언트에 노출하는 샘플/데모용입니다.
> 실제 서비스에서는 서버 사이드 발급(`/jwt/create`)만 사용하세요.
>
> 홈(`/`)에 삽입된 iframe(`https://v.kr.kollus.com/dzgAE3b9`)은 Kollus 가 제공하는 공개 데모 콘텐츠로,
> 키 설정 없이 플레이어 동작을 바로 확인할 수 있습니다.

## 관련 문서

- [Kollus API 레퍼런스](https://docs.kollus.com)
- [Kollus 관리자 콘솔](https://admin.kollus.com)
