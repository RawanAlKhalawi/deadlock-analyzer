#!/bin/bash
docker build -t deadlock-analyzer .
docker tag deadlock-analyzer rawanalkhalawi/deadlock-analyzer:latest
docker push rawanalkhalawi/deadlock-analyzer:latest
