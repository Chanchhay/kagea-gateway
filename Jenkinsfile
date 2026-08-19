pipeline {
    agent any

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    environment {
        REGISTRY   = 'asia-southeast1-docker.pkg.dev'
        PROJECT_ID = 'project-6f8e390c-7ad4-4d23-b1b'
        REPOSITORY = 'backend-images'
        APP_NAME   = 'ai-career-gateway'

        DEPLOY_HOST = 'chanchhay@10.148.0.2'
        DEPLOY_DIR  = '/opt/apps/ai-career'

        SSH_CRED = 'ipos-server-ssh'

        TAG_KEY = 'GATEWAY_IMAGE_TAG'
        SERVICE = 'gateway'

        HEALTH_PORT = '8090'
    }

    stages {

        stage('Prepare') {
            steps {
                sh '''
                    chmod +x gradlew

                    echo "=== Java ==="
                    java -version

                    echo "=== Gradle ==="
                    ./gradlew --version
                '''
            }
        }


        stage('Build JAR') {
            steps {
                sh '''
                    ./gradlew \
                        bootJar \
                        --no-daemon \
                        --build-cache
                '''
            }
        }


        stage('Prepare Docker Artifact') {
            steps {
                sh '''
                    rm -f app.jar

                    JAR_FILE="$(find build/libs \
                        -maxdepth 1 \
                        -type f \
                        -name '*.jar' \
                        ! -name '*-plain.jar' \
                        -print \
                        -quit)"

                    if [ -z "$JAR_FILE" ]; then
                        echo "ERROR: Spring Boot JAR not found"
                        exit 1
                    fi

                    echo "Using JAR: $JAR_FILE"

                    cp "$JAR_FILE" app.jar

                    ls -lh app.jar
                '''
            }
        }


        stage('Image Tag') {
            steps {
                script {
                    env.GIT_SHA = sh(
                        script: 'git rev-parse --short=12 HEAD',
                        returnStdout: true
                    ).trim()

                    env.IMAGE_REPO =
                        "${REGISTRY}/${PROJECT_ID}/${REPOSITORY}/${APP_NAME}"

                    env.IMAGE =
                        "${env.IMAGE_REPO}:${env.GIT_SHA}"

                    echo "Git SHA: ${env.GIT_SHA}"
                    echo "Docker image: ${env.IMAGE}"
                }
            }
        }


        stage('Docker Build') {
            when {
                branch 'master'
            }

            steps {
                sh '''
                    docker build \
                        -f Dockerfile.ci \
                        -t "$IMAGE" \
                        -t "$IMAGE_REPO:latest" \
                        .
                '''
            }
        }


        stage('Push Image') {
            when {
                branch 'master'
            }

            steps {
                sh '''
                    docker push "$IMAGE"
                    docker push "$IMAGE_REPO:latest"
                '''
            }
        }


        stage('Deploy') {
            when {
                branch 'master'
            }

            steps {
                sshagent(credentials: ["${SSH_CRED}"]) {
                    sh '''
                        echo "Deploying $IMAGE"

                        ssh \
                          -o StrictHostKeyChecking=accept-new \
                          "$DEPLOY_HOST" \
                          "DEPLOY_DIR='$DEPLOY_DIR' \
                           GIT_SHA='$GIT_SHA' \
                           TAG_KEY='$TAG_KEY' \
                           SERVICE='$SERVICE' \
                           HEALTH_PORT='$HEALTH_PORT' \
                           bash -s" <<'REMOTE'

set -euo pipefail

cd "$DEPLOY_DIR"

exec 9>.deploy.lock

if ! flock -w 120 9; then
    echo "ERROR: Could not acquire deployment lock"
    exit 1
fi

echo "Deployment lock acquired"


if [ ! -f .env ]; then
    echo "ERROR: .env does not exist"
    exit 1
fi


PREVIOUS_TAG="$(grep "^${TAG_KEY}=" .env \
    | head -1 \
    | cut -d= -f2- || true)"


update_tag() {

    local value="$1"

    if grep -q "^${TAG_KEY}=" .env; then

        sed -i \
          "s|^${TAG_KEY}=.*|${TAG_KEY}=${value}|" \
          .env

    else

        echo "${TAG_KEY}=${value}" >> .env

    fi
}


echo "Previous tag: ${PREVIOUS_TAG:-none}"
echo "New tag: $GIT_SHA"

update_tag "$GIT_SHA"


echo
echo "Validating Compose..."

docker compose config >/dev/null


echo
echo "Pulling gateway..."

docker compose pull "$SERVICE"


echo
echo "Deploying gateway..."

docker compose up \
    -d \
    --no-deps \
    "$SERVICE"


echo
echo "Waiting for gateway..."

HEALTHY=0

for i in $(seq 1 30); do

    CID="$(docker compose ps -q "$SERVICE")"

    if [ -z "$CID" ]; then
        break
    fi

    STATE="$(docker inspect \
        --format '{{.State.Status}}' \
        "$CID")"

    if [ "$STATE" != "running" ]; then
        break
    fi

    CODE="$(
        docker compose exec \
            -T \
            "$SERVICE" \
            sh -c "
                curl \
                    -s \
                    -o /dev/null \
                    -w '%{http_code}' \
                    http://127.0.0.1:${HEALTH_PORT}/ \
                    || true
            " 2>/dev/null || true
    )"

    case "$CODE" in

        2*|3*|4*)

            echo "Gateway responded HTTP $CODE"

            HEALTHY=1

            break

            ;;

    esac

    echo "Waiting... $i/30"

    sleep 3
done


if [ "$HEALTHY" -ne 1 ]; then

    echo "Gateway health check failed"

    docker compose ps

    docker compose logs \
        --tail=200 \
        "$SERVICE"

    if [ -n "$PREVIOUS_TAG" ] \
       && [ "$PREVIOUS_TAG" != "$GIT_SHA" ]; then

        echo "Rolling back to $PREVIOUS_TAG"

        update_tag "$PREVIOUS_TAG"

        docker compose pull "$SERVICE" || true

        docker compose up \
            -d \
            --no-deps \
            "$SERVICE" || true
    fi

    exit 1
fi


echo "Checking gateway stability..."

FIRST_CID="$(docker compose ps -q "$SERVICE")"

sleep 15

SECOND_CID="$(docker compose ps -q "$SERVICE")"


if [ -z "$SECOND_CID" ] \
   || [ "$FIRST_CID" != "$SECOND_CID" ]; then

    echo "Gateway restarted during stability check"

    docker compose logs \
        --tail=200 \
        "$SERVICE"

    if [ -n "$PREVIOUS_TAG" ] \
       && [ "$PREVIOUS_TAG" != "$GIT_SHA" ]; then

        update_tag "$PREVIOUS_TAG"

        docker compose pull "$SERVICE" || true

        docker compose up \
            -d \
            --no-deps \
            "$SERVICE" || true
    fi

    exit 1
fi


STATE="$(docker inspect \
    --format '{{.State.Status}}' \
    "$SECOND_CID")"

RESTARTS="$(docker inspect \
    --format '{{.RestartCount}}' \
    "$SECOND_CID")"


if [ "$STATE" != "running" ] \
   || [ "$RESTARTS" != "0" ]; then

    echo "Gateway unstable"

    echo "State: $STATE"
    echo "Restarts: $RESTARTS"

    docker compose logs \
        --tail=200 \
        "$SERVICE"

    if [ -n "$PREVIOUS_TAG" ] \
       && [ "$PREVIOUS_TAG" != "$GIT_SHA" ]; then

        update_tag "$PREVIOUS_TAG"

        docker compose pull "$SERVICE" || true

        docker compose up \
            -d \
            --no-deps \
            "$SERVICE" || true
    fi

    exit 1
fi


echo
echo "Gateway deployment successful"

docker compose ps

echo
echo "Running image:"

docker inspect \
    "$SECOND_CID" \
    --format '{{.Config.Image}}'

REMOTE
                    '''
                }
            }
        }
    }


    post {

        success {
            echo '================================'
            echo 'AI CAREER GATEWAY CI/CD SUCCESS'
            echo "Image: ${env.IMAGE}"
            echo '================================'
        }

        failure {
            echo '================================'
            echo 'AI CAREER GATEWAY CI/CD FAILED'
            echo '================================'
        }

        always {
            sh '''
                rm -f app.jar
                docker image prune -f || true
            '''
        }
    }
}
