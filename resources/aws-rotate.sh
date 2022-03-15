#!/bin/bash
# dependencies: aws-cli, jq, java, curl
#
# You should run 'aws --profile ${AWS_PROFILE} configure'
# before attempting to run this rotation script

usage() {
    echo "usage: $0 aws_profile [region || us-west-2]"
    exit 1
}

find_cmd() {
	command -v $1 || { echo "$1 must be installed"; exit 1; }
}

aws_rollback() {
	set +e
	mv $AWS_PREV_CREDENTIALS_FILE ${AWS_CREDENTIALS_FILE}
	$AWS iam delete-access-key --access-key-id ${AWS_ACCESS_KEY_ID}
	echo "Failed to set new access key: rolling back"
	exit 1
}

[ $# -gt 0 ] || usage

AWS_PROFILE=${1}
AWS_CREDENTIALS_FILE=${HOME}/.aws/credentials
AWS_PREV_CREDENTIALS_FILE=${HOME}/.aws/prev-credentials

AWS="$(find_cmd aws) --profile ${AWS_PROFILE}"
JQ=$(find_cmd jq)

# check if profile exists
res=$($AWS configure get aws_access_key_id) || {
	echo "Failed to get current access key"
	echo "${res}"
	echo ""
	echo "Please run '${AWS} configure' with the proper access key and secret"
	echo "before attempting rotation again"
	exit 1
}

if [ -f "$AWS_PREV_CREDENTIALS_FILE" ]; then
    echo "Deleting previous access key"
    AWS_PREV_ACCESS_KEY_ID=$(AWS_SHARED_CREDENTIALS_FILE=$AWS_PREV_CREDENTIALS_FILE \
        $AWS configure get aws_access_key_id) && \
        $AWS iam delete-access-key --access-key-id $AWS_PREV_ACCESS_KEY_ID || {
            echo "Failed to delete previous access key"
            exit 1
        }
    rm $AWS_PREV_CREDENTIALS_FILE
fi

echo "Creating new access key"
res=$($AWS iam create-access-key) || {
	echo "Failed to create new access key"
	echo "${res}"
	exit 1
}

echo "Backing up current access key"
cp $AWS_CREDENTIALS_FILE $AWS_PREV_CREDENTIALS_FILE

# from this point on any failure indicates some issue with the newly generated key
# so be VERY careful, rollback back to the previous key for any failure
set -e
trap aws_rollback EXIT INT HUP

AWS_ACCESS_KEY_ID=$(echo "${res}" | $JQ -r '.AccessKey.AccessKeyId')
AWS_SECRET_ACCESS_KEY=$(echo "${res}" | $JQ -r '.AccessKey.SecretAccessKey')
AWS_ACCESS_KEY_STATUS=$(echo "${res}" | $JQ -r '.AccessKey.Status')
[ "${AWS_ACCESS_KEY_STATUS}" = "Active" ] || {
	echo "Unrecognized access key status '${AWS_ACCESS_KEY_STATUS}'"
	exit 1
}

[ "${AWS_ACCESS_KEY_ID}" != "null" -a "${AWS_SECRET_ACCESS_KEY}" != "null" ] || {
    echo "Access key or secret is null"
    exit 1
}

echo "Setting new access key"
$AWS configure set output json
$AWS configure set aws_access_key_id ${AWS_ACCESS_KEY_ID}
$AWS configure set aws_secret_access_key ${AWS_SECRET_ACCESS_KEY}

# make sure new key is valid
sleep 10 # give time to propogate
res=$($AWS iam list-access-keys) || {
	echo "Newly generated access key failed to list keys"
	echo "${res}"
	exit 1
}

# new access key looks good, no longer need to rollback on failure
set +e
trap - EXIT INT HUP

echo ""
echo "AWS firmware signing key rotated"
