
echo $CI_JOB_TOKEN

curl --request POST \
     --header "PRIVATE-TOKEN: $CI_JOB_TOKEN" \
     --data name="file.png" \
     --data url="https://confluence.cosylab.com/display/NUSANO/Nusano+Home?preview=/16679771/16679826/Screenshot_2019-03-20_13-51-59.png" \
     "https://git.cosylab.com/api/v4/projects/2263/releases/$CI_COMMIT_TAG/assets/links"
