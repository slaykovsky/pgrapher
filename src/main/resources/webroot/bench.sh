#!/usr/bin/env bash
set -e

RUN_DIR=./run
TMP=/tmp
PGRAPHER_API=http://pgrapher.slaykovsky.com/api/tests

sudo apt-get install -qq -y --no-install-recommends \
    apt-transport-https \
    ca-certificates \
    curl \
    software-properties-common
curl -fsSL https://apt.dockerproject.org/gpg | sudo apt-key add -
apt-key fingerprint 58118E89F3A912897C070ADBF76221572C52609D
sudo add-apt-repository -y \
       "deb https://apt.dockerproject.org/repo/ \
       ubuntu-$(lsb_release -cs) \
       main"
sudo apt update -y \
    && sudo apt install -y sysbench docker-engine

mkdir -p ${RUN_DIR}
pushd ${RUN_DIR}

for run in 1 2 3
do
  for threads in 1 2 4
  do
    echo "Performing random read/write test #${run}"
    test="io"
    result_file="${TMP}/${test}_${threads}_${run}.result"
    sysbench --test=fileio --file-total-size=16G \
      --file-test-mode=rndwr --max-time=60 \
      --max-requests=0 --file-block-size=4K \
      --file-num=64 --num-threads=${threads} \
      run > ${result_file}
    result=$(grep -Eh "Requests/sec" ${result_file} | grep -Eoh [0-9]+\.[0-9]+)
    curl -H "Content-Type: application/json" \
        -d '{"hostname": "'${HOSTNAME}'", "test": "'${test}'", "threads": "'${threads}'", "run": "'${run}'", "result": "'${result}'"}' \
        ${PGRAPHER_API}
    rm -f ./test_file* ${result_file}

    echo "Performing OLTP test"
    test="oltp"
    result_file="${TMP}/${test}_${threads}_${run}.result"
    sudo docker run --name sysbenchdb \
        -e MYSQL_DATABASE=sysbench \
        -e MYSQL_USER=sysbench \
        -e MYSQL_PASSWORD=sysbench \
        -e MYSQL_ROOT_PASSWORD=root \
        -p 3306:3306 \
        -d mysql:latest
    sleep 15
    sysbench --test=oltp --db-driver=mysql \
        --oltp-table-size=1000000 \
        --mysql-db=sysbench \
        --mysql-user=sysbench \
        --mysql-password=sysbench \
        --mysql-host=127.0.0.1 \
        --num-threads=${threads} prepare
    sysbench --test=oltp --db-driver=mysql \
      --oltp-table-size=1000000 --mysql-db=sysbench \
      --mysql-user=sysbench --mysql-password=sysbench \
      --max-time=60 --max-requests=0 --mysql-host=127.0.0.1 \
      --num-threads=${threads} \
      run > ${result_file}
    result=$(grep -hE "transactions:" ${result_file} | grep -hEo "[0-9]+\.[0-9]+")
    curl -H "Content-Type: application/json" \
        -d '{"hostname": "'${HOSTNAME}'", "test": "'${test}'", "threads": "'${threads}'", "run": "'${run}'", "result": "'${result}'"}' \
        ${PGRAPHER_API}
    rm -f ./test_file* ${result_file}
    sudo docker kill sysbenchdb
    sudo docker rm -f sysbenchdb

    echo "Performing CPU test #${run}"
    test="primes"
    result_file="${TMP}/${test}_${threads}_${run}r.result"
    sysbench --test=cpu \
      --cpu-max-prime=100000 \
      --num-threads=${threads} \
      run > ${result_file}
    result=$(grep -hE "total time:" ${result_file} | grep -hEo "[0-9]+\.[0-9]+")
    curl -H "Content-Type: application/json" \
        -d '{"hostname": "'${HOSTNAME}'", "test": "'${test}'", "threads": "'${threads}'", "run": "'${run}'", "result": "'${result}'"}' \
        ${PGRAPHER_API}
    rm -f ./test_file* ${result_file}

    echo "Performing RAM read test #${run}"
    test="ram_read"
    result_file="${TMP}/${test}_${threads}_${run}r.result"
    sysbench --test=memory \
      --memory-block-size=4K \
      --memory-scope=global \
      --memory-total-size=512G \
      --memory-oper=read \
      --num-threads=${threads} \
      run > ${result_file}
    result=$(grep -hEo "[0-9]+.[0-9]+ ops/sec" ${result_file} | grep -hEo "[0-9]+\.[0-9]+")
    curl -H "Content-Type: application/json" \
        -d '{"hostname": "'${HOSTNAME}'", "test": "'${test}'", "threads": "'${threads}'", "run": "'${run}'", "result": "'${result}'"}' \
        ${PGRAPHER_API}
    rm -f ./test_file* ${result_file}

    echo "Performing RAM write test #${run}"
    test="ram_write"
    result_file="${TMP}/${test}_${threads}_${run}r.result"
    sysbench --test=memory \
      --memory-block-size=4K \
      --memory-scope=global \
      --memory-total-size=512G \
      --memory-oper=write \
      --num-threads=${threads} \
      run > ${result_file}
    result=$(grep -hEo "[0-9]+.[0-9]+ ops/sec" ${result_file} | grep -hEo "[0-9]+\.[0-9]+")
    curl -H "Content-Type: application/json" \
        -d '{"hostname": "'${HOSTNAME}'", "test": "'${test}'", "threads": "'${threads}'", "run": "'${run}'", "result": "'${result}'"}' \
        ${PGRAPHER_API}
    rm -f ./test_file* ${result_file}
  done
done
popd