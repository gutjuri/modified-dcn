#!/bin/sh

# This script will generate a docker-compose file and config files
# for DCN nodes according to the specified parameters.
# After that, it will execute 5 runs of such a network and write the results
# (time consumed) to an output file.

# Adjust parameters and output file here
bpr=$(expr 8 \* 1024)
n=3
ti=100 #ms
out_file=../bericht/benchmarks/n"$n"_bpr"$bpr"_ti"$ti".txt

gen_config_files() {
  for peernr in $(seq 1 $1); do
    PEERS="$PEERS 172.28.1.$peernr:$(expr 1336 + $peernr)"
  done

  mkdir -p benchmarks
  cp docker-compose-template.yml docker-compose-mat.yml

  for peer in $PEERS; do
    PEERS_WO_SELF=$(echo $PEERS | tr ' ' '\0' | tr -d '\n' | grep -vz $peer | sed -z -e 's/\(.*\)/  - \1\\n/')
    PEER_HOSTNAME=$(echo $peer | sed s/:.\*//)
    PEER_PORT=$(echo $peer | sed s/.\*://)
    BM_CFG_FILENAME=bm-config-$PEER_HOSTNAME.yml
    cp config1-template.yml benchmarks/$BM_CFG_FILENAME


    sed -i "s/# peers-inp-here/$PEERS_WO_SELF/" benchmarks/$BM_CFG_FILENAME
    sed -i "s/#OWN_IP_HERE/$peer/" benchmarks/$BM_CFG_FILENAME
    sed -i "s/#K_HERE/$1/" benchmarks/$BM_CFG_FILENAME
    sed -i "s/#BPR_HERE/$2/" benchmarks/$BM_CFG_FILENAME
    sed -i "s/#RI_HERE/$3/" benchmarks/$BM_CFG_FILENAME

    sed -i "s/#clnt_here/  c$(echo $peer | md5sum | cut -d' ' -f1):\\n#clnt_here/" docker-compose-mat.yml
    sed -i "s/#clnt_here/    build: .\\n#clnt_here/" docker-compose-mat.yml
    sed -i "s/#clnt_here/    environment: {EDCN_CONFIG: $BM_CFG_FILENAME}\\n#clnt_here/" docker-compose-mat.yml
    sed -i "s/#clnt_here/    ports: [$PEER_PORT:$PEER_PORT]\\n#clnt_here/" docker-compose-mat.yml
    sed -i "s/#clnt_here/    volumes: [.\/benchmarks\/$BM_CFG_FILENAME:\/app\/$BM_CFG_FILENAME]\\n#clnt_here/" docker-compose-mat.yml
    sed -i "s/#clnt_here/    networks: {testing_net: {ipv4_address: $PEER_HOSTNAME}}\\n#clnt_here/" docker-compose-mat.yml
    sed -i "s/#clnt_here/    cap_add: [NET_ADMIN]\\n#clnt_here/" docker-compose-mat.yml
  done
  echo "$PEERS"
}


gen_config_files $n $bpr $ti

# for demo purposes we end right here
exit

for i in $(seq 1 5); do
  TIME=$(timeout 5m docker-compose -f docker-compose-mat.yml up --build \
    | grep -Eo "[0-9]*ms" | tail -n1)
  echo $TIME >> "$out_file"
  echo "done with run $i with n=$n bpr=$bpr ti=$ti"
  docker container stop $(docker ps --format "table {{.ID}}")
done