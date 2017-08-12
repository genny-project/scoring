#!/bin/sh
echo "RUNNING DOCKER-ENTRYPOINT"
#set -e;

KEYCLOAK_JSON_FILE=/realm/keycloak.json
CLUSTER_XML_FILE=/cluster.xml
JAVA_OPT_KEYCLOAK_PATH=-Dswarm.keycloak.json.path=${KEYCLOAK_JSON_FILE}


# change the package.json file
function escape_slashes {
    /bin/sed 's/\//\\\//g'
}

function change_line {
  eval OLD_LINE_PATTERN="$1"
  eval NEW_LINE="$2"
  eval FILE="$3"

    local NEW=$(echo "${NEW_LINE}" | escape_slashes)
    /bin/sed -i  '/'"${OLD_LINE_PATTERN}"'/s/.*/'"${NEW}"'/' "${FILE}"
}

function change_line2 {
  eval OLD_LINE_PATTERN="$1"
  eval NEW_LINE="$2"
  eval FILE="$3"

    local NEW=$(echo "${NEW_LINE}" | escape_slashes)
    /bin/sed -i  '/'"${OLD_LINE_PATTERN}"'/s/.*/'"${NEW}"'/' "${FILE}"
}

if [ -z "${KEYCLOAKURL}" ]; then
   echo "No KEYCLOAKURL given. No change to keycloak.json"
else
   OLD_LINE_KEY="auth-server-url"
   NEW_LINE="\"auth-server-url\": \"${KEYCLOAKURL}\","
   change_line "\${OLD_LINE_KEY}" "\${NEW_LINE}" "\${KEYCLOAK_JSON_FILE}"
fi

   OLD_LINE_KEY="<interface>10\.1\.1\.\*<\/interface>"
   NEW_LINE="<interface>${HOSTIP}</interface>"
   change_line2 "\${OLD_LINE_KEY}" "\${NEW_LINE}" "\${CLUSTER_XML_FILE}"

#Set some ENV by extracting from keycloak.json file
export KEYCLOAK_REALM=`jq '.realm' /realm/keycloak.json`
export KEYCLOAK_URL=`jq '.["auth-server-url"]' ${KEYCLOAK_JSON_FILE}`
export KEYCLOAK_CLIENTID=`jq '.resource' ${KEYCLOAK_JSON_FILE}`
export KEYCLOAK_SECRET=`jq '.credentials.secret' ${KEYCLOAK_JSON_FILE}`

echo "KEYCLOAK REALM= ${KEYCLOAK_REALM}"
echo "KEYCLOAK URL= ${KEYCLOAK_URL}"
echo "KEYCLOAK CLIENTID= ${KEYCLOAK_CLIENTID}"
echo "KEYCLOAK SECRET= ${KEYCLOAK_SECRET}"

command="$1"; 
if [ "$command" != "java" ]; then 
   echo "ERROR: command must start with: java"; 
   exit 1; 
fi; 

exec "$@"
