## Project Structure

```text
AI-Visual-Validation-Solution/
├── src/
│   ├── main/
│   │   ├── java/
│   │   └── resources/
│   └── test/
├── logs/
├── pom.xml
├── .gitignore
├── .env.example
└── README.md
```

## Prerequisites

* Java 17 or later
* Maven
* Google Gemini API Key

## Configuration

Create a `.env` file in the project root:

```env
GEMINI_API_KEY=YOUR_GEMINI_API_KEY
```

The `.env` file is ignored by Git for security reasons.

## Installation

```bash
git clone https://github.com/jaigora24/AI-Visual-Validation-Solution.git
cd AI-Visual-Validation-Solution
mvn clean install
```

## Security

Sensitive information such as API keys are never stored in the repository. Configure secrets locally using a `.env` file.
