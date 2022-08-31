def date_backup = new Date().format('yyyy_MM_dd_HH_mm_ss');
def db_backup_directory = "/var/lib/db_backup"
def max_day_backup = 7      //одна неделя
def max_weekly_backup = 28  // за 4 недели
def max_years_backup = 365  //один год
def required_package = "python3 python3-pip default-mysql-client postgresql-client"
//  backblaze-b2

  parameters {
      booleanParam(name: 'loc_install_packege', defaultValue: false)
      choice(name: 'db_name', choices: ['mysql', 'postgres'], description: 'выбрать БД') 
      choice(name: 'choice_save_backup', choices: ['local', 'b2'], description: 'куда сохранять бэкап') 
      string(name: 'db_mysql_hosts', description: 'хост базы данных mysql', defaultValue: "${params.db_mysql_hosts}")
      string(name: 'db_mysql_port', description: 'порт базы данных mysql', defaultValue: "${params.db_mysql_port}")
      string(name: 'db_postgres_hosts', description: 'хост базы данных postgres', defaultValue: "${params.db_postgres_hosts}")
      string(name: 'db_postgres_port', description: 'порт базы данных postgres', defaultValue: "${params.db_postgres_port}")
  }

  switch (params.db_name) {
      case "mysql": db_cred = "db_mysql_cred"; db_name_client = "mysqldump"; db_hosts = "${params.db_mysql_hosts}"; file_format = "sql.gz"; break;
      case "postgres": db_cred = "db_postgres_cred"; db_name_client = "pg_dump"; db_hosts = "${params.db_postgres_hosts}"; file_format = "sql.gz"; break;
      default: break;
  }

pipeline {
agent {label 'master'}
// options { timestamps() }

// triggers {
//     cron('H 01 * * *')
// }

stages {
  stage ('check package') { 
    when {
        expression { 
            return params.loc_install_packege
        }
    }
      steps {
        sh """#!/usr/bin/env bash
          set -e
          set -o pipefail
#          if [ -f /etc/redhat-release ]; then
#            yum update
#          fi
#          if [ -f /etc/lsb-release ]; then
#            apt-get update
#          fi
          set -e
          OS=\$(egrep '^(NAME)=' /etc/os-release | sed 's/[^=]*=//' | sed 's/"//g'| awk '{print \$1}')
          if [[ \$OS == "Ubuntu" || \$OS == "Debian" ]]
          then
              pack=($required_package)
              for package in \${pack[@]}
              do
                  if [ \$(dpkg-query -W -f='\${Status}' \$package 2>/dev/null | grep -c "ok installed") -eq 0 ]
                  then
                    apt-get update && apt-get install -y \$package;
                  else
                    echo "package - \$package - installed";
                  fi
              done
          fi
        """
        sh """#!/usr/bin/env bash
          if [[ \$(pip3 list | grep "b2 ") ]]; then
          echo "b2 package exist"
          else
          echo "b2 package will by installed"
          pip3 install b2
          fi
        """
      }
  }

  stage ('create backup') {
      steps {
          script {
            //  for (x in db_hosts){
            println "address database backup : " + db_hosts
            name_backup = sh (
            script: "echo ${db_hosts} | awk -F '.' '{print \$1}'",
            returnStdout: true
            ).trim()
            println "name folder from backup: " + name_backup
            FULL_NAME_BACKUP = "${name_backup}_full_${date_backup}.${file_format}"
            FULL_DIR_NAME_BACKUP = "${params.db_name}/${name_backup}/${FULL_NAME_BACKUP}"
            println "FULL_NAME_BACKUP: " + FULL_NAME_BACKUP
            println "FULL_DIR_NAME_BACKUP: " + FULL_DIR_NAME_BACKUP
            withCredentials([usernamePassword(credentialsId: "${name_backup}", passwordVariable: 'dbpassword', usernameVariable: 'dbuser')]) {
            if ( params.db_name == 'mysql') {
            sh """#!/bin/bash
              set -e
              set -o pipefail
              list_bases=\$(mysql -u ${dbuser} -p${dbpassword} -h ${db_hosts} --port ${params.db_mysql_port} -e "show databases" | grep -Ev "^(Database|sys|defaultdb|mysql|performance_schema|information_schema)\$")
              echo "List include databases in backup:\n\$list_bases"
              mysqldump -u ${dbuser} -p${dbpassword} -h ${db_hosts} --port=${params.db_mysql_port} --databases \${list_bases} --single-transaction --quick --lock-tables=false | gzip > ${FULL_NAME_BACKUP}
              echo "create file backup: \n\$(ls -lh ${FULL_NAME_BACKUP})"
              """
            }
            if ( params.db_name == 'postgres') {
            sh """#!/bin/bash
              set -e
              set -o pipefail
              ###PGPASSWORD=${dbpassword} pg_dumpall -h ${db_hosts} -p ${params.db_postgres_port} -U ${dbuser} > ${FULL_NAME_BACKUP}
              list_bases=\$(PGPASSWORD='${dbpassword}' psql -h ${db_hosts} -U ${dbuser} -d postgres -t -c "select datname from pg_database where not datistemplate" | grep -vE "rdsadmin|postgres" | grep '\\S' | awk '{\$1=\$1};1')
              echo "List include databases in backup:\n\$list_bases"
              for db in \$list_bases; do
              PGPASSWORD='${dbpassword}' pg_dump -h ${db_hosts} -p ${params.db_postgres_port} -U ${dbuser} -d \${db[@]} > ${name_backup}_\${db}_full_${date_backup}.sql
              done
              echo "db file create: \n \$(ls -lh)"
              tar -czvf ${FULL_NAME_BACKUP} ${name_backup}_*_${date_backup}.sql
              """
            }
              echo "create file from backup: ${FULL_NAME_BACKUP}"
            }
            SHAMQL = sh ( script: "sha1sum ${FULL_NAME_BACKUP} | awk '{print \$1}'", returnStdout: true ).trim()
            println "cheksum backup : " + SHAMQL
          }
      // }
      }
  }

  stage ('copy backup to local directory') {
    steps {
      script {
      if ( params.choice_save_backup == 'local') {
      // check folders
        sh """#!/bin/bash
          set -e
          set -o pipefail
          backup_folder=('${name_backup}' '${name_backup}/week' '${name_backup}/month' '${name_backup}/year')
          for folder in \${backup_folder[@]}
          do
            if [[ ! -d ${db_backup_directory}/${params.db_name}/\$folder ]]
            then
              echo "The folders \${folder} not exist, create folder \${folder}"
              mkdir -p ${db_backup_directory}/${params.db_name}/\$folder
              #echo -e "folder create: \\n \$(find ${db_backup_directory}/${params.db_name}/* -type d -print)"
            else
              echo "The dir \${folder} exist!"
            fi
          done
        """
      }
      withCredentials([usernamePassword(credentialsId: "b2_cred", passwordVariable: 'B2_APP_KEY', usernameVariable: 'B2_ACC_ID')]) {
        // copy backup
        sh """#!/bin/bash
          set -e
          set -o pipefail
          echo \$(ls -lh ${FULL_NAME_BACKUP})
          #!!!СЛЕДУЮЩИЙ ГОД!!!
          if [[ \$(date +%Y) != \$(date +%Y -d '+1 days') ]]
          then
            if [[ ${params.choice_save_backup} == 'local' ]]; then
            echo "copy ${FULL_NAME_BACKUP} to ${db_backup_directory}/${params.db_name}/${name_backup}/year/"
            cp ${FULL_NAME_BACKUP} ${db_backup_directory}/${params.db_name}/${name_backup}year/
            elif [[ ${params.choice_save_backup} == 'b2' ]]; then
             echo "starting upload db to b2 db-years at ${FULL_DIR_NAME_BACKUP}"
             /usr/local/bin/b2 authorize-account ${B2_ACC_ID} ${B2_APP_KEY}
             /usr/local/bin/b2 upload_file --sha1 ${SHAMQL} db-years ${FULL_NAME_BACKUP} ${FULL_DIR_NAME_BACKUP}
             echo "finished uploading db to b2 db-years at ${FULL_DIR_NAME_BACKUP}"
            fi
          #!!!СЛЕДУЮЩИЙ МЕСЯЦ!!!
          elif [[ \$(date +%m) != \$(date +%m -d '+1 days') ]]
          then
            if [[ ${params.choice_save_backup} == 'local' ]]; then
            echo "copy ${FULL_NAME_BACKUP} to ${db_backup_directory}/${params.db_name}/${name_backup}/month"
            cp ${FULL_NAME_BACKUP} ${db_backup_directory}/${params.db_name}/${name_backup}/month/
            elif [[ ${params.choice_save_backup} == 'b2' ]]; then
             echo "starting upload db to b2 db-months at ${FULL_DIR_NAME_BACKUP}"
             /usr/local/bin/b2 authorize-account ${B2_ACC_ID} ${B2_APP_KEY}
             /usr/local/bin/b2 upload_file --sha1 ${SHAMQL} db-months ${FULL_NAME_BACKUP} ${FULL_DIR_NAME_BACKUP}
             echo "finished uploading db to b2 db-months at ${FULL_DIR_NAME_BACKUP}"
            fi
          #!!!СЛЕДУЮЩАЯ НЕДЕЛЯ!!!
          elif [[ \$(date +%V) != \$(date +%V -d '+1 days') ]]
          then
            if [[ ${params.choice_save_backup} == 'local' ]]; then
            echo "copy ${FULL_NAME_BACKUP} to ${db_backup_directory}/${params.db_name}/${name_backup}/week"
            cp ${FULL_NAME_BACKUP} ${db_backup_directory}/${params.db_name}/${name_backup}/week/
            elif [[ ${params.choice_save_backup} == 'b2' ]]; then
             echo "starting upload db to b2 db-weeks at ${FULL_DIR_NAME_BACKUP}"
             /usr/local/bin/b2 authorize-account ${B2_ACC_ID} ${B2_APP_KEY}
             /usr/local/bin/b2 upload_file --sha1 ${SHAMQL} db-weeks ${FULL_NAME_BACKUP} ${FULL_DIR_NAME_BACKUP}
             echo "finished uploading db to b2 db-weeks at ${FULL_DIR_NAME_BACKUP}"
            fi
          #!!!ЕЖЕДНЕВНЫЙ БЭКАП!!!
          else
            if [[ ${params.choice_save_backup} == 'local' ]]; then
            echo "copy ${FULL_NAME_BACKUP} to ${db_backup_directory}/${params.db_name}/${name_backup}"
            cp ${FULL_NAME_BACKUP} ${db_backup_directory}/${params.db_name}/${name_backup}/
            elif [[ ${params.choice_save_backup} == 'b2' ]]; then
             echo "starting upload db to b2 db-days at ${FULL_DIR_NAME_BACKUP}"
             /usr/local/bin/b2 authorize-account ${B2_ACC_ID} ${B2_APP_KEY}
             /usr/local/bin/b2 upload_file --sha1 ${SHAMQL} db-days ${FULL_NAME_BACKUP} ${FULL_DIR_NAME_BACKUP}
             echo "finished uploading db to b2 db-days at ${FULL_DIR_NAME_BACKUP}"
            fi
          fi
        """
        // example copy backblaze(b2)
        // sh """
        // B2_ACC_ID=""
        // B2_APP_KEY=""
        // B2_BUCKET_NAME="" 
        // echo "starting upload db to b2 at ${FULL_NAME_BACKUP}"
        // /usr/local/bin/b2 authorize-account $B2_ACC_ID $B2_APP_KEY
        // /usr/local/bin/b2 upload_file --sha1 ${SHAMQL} $B2_BUCKET_NAME . ${FULL_NAME_BACKUP}
        // echo "finished uploading db to b2 at ${FULL_NAME_BACKUP}"
        // exit 0
        // """
      }
      }
    }
  }

  stage ('delete old file backup') {
    when {
          expression { params.choice_save_backup == 'local' }
    }
    steps {
      script {
      // if ( params.choice_save_backup == 'local') {
        sh """#!/bin/bash
          set -e
          set -o pipefail
        #7 ежедневных бэкапов
          delete_days_file=\$(find ${db_backup_directory}/${params.db_name}/${name_backup} -type f -name "*.${file_format}" -mtime +${max_day_backup}) 
          echo "Delete local database backups older than ${max_day_backup} days."
          find ${db_backup_directory}/${params.db_name}/${name_backup} -type f -name "*.${file_format}" -mtime +${max_day_backup} -delete
          echo -e "file delete: \\n\$delete_days_file"
          #echo -e "\$delete_days_file"
        #4 недельных бэкапа
          delete_weekly_file=\$(find ${db_backup_directory}/${params.db_name}/${name_backup}/week -type f -name "*.${file_format}" -mtime +${max_weekly_backup}) 
          echo "Delete local database backups older than ${max_weekly_backup} days."
          find ${db_backup_directory}/${params.db_name}/${name_backup}/week -type f -name "*.${file_format}" -mtime +${max_weekly_backup} -delete
          echo -e "file delete: \\n\$delete_weekly_file"
        #12 бэкапов месячных старше удаляем
          delete_month_file=\$(find ${db_backup_directory}/${params.db_name}/${name_backup}/month -type f -name "*.${file_format}" -mtime +${max_years_backup}) 
          echo "Delete local database backups older than ${max_years_backup} days."
          find ${db_backup_directory}/${params.db_name}/${name_backup}/month -type f -name "*.${file_format}" -mtime +${max_years_backup} -delete
          echo -e "file delete: \\n\$delete_month_file"
        """
      // }
      }
    }
  }

  }   //end stages
  post {
      always {
          // cleanWs disableDeferredWipeout: true, deleteDirs: true  
          // sh "rm -rf \${PWD}/*"
      }
    //   success {
          // slackSend color: "#00FF00", channel: "#channel-name", message: "Backup create ${FULL_NAME_BACKUP} from host:${db_hosts} successfully"
    //   }
    // failure {
    //     slackSend failOnError:true color: "#FF0000", channel: "#channel-name", message:"Backup create ${FULL_NAME_BACKUP} rom host:${db_hosts} failed"
    // }
  }
}   //end pipeline
