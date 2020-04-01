#!/bin/bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd $DIR
./flink list -r | grep -oP ": (.*) :" | cut -d":" -f2 | cut -d" " -f2 | xargs -I {} ./flink cancel {}
