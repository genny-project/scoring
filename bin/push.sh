#!/bin/bash

if [ -z "${1}" ]; then
   version="latest"
else
   version="${1}"
fi


docker push gennyproject/scoring:"${version}"
docker tag  gennyproject/scoring:"${version}"  gennyproject/scoring:latest
docker push gennyproject/scoring:latest

