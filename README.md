# Manifest Proxy

# For god and love, Please Please Please use SSL

Create an Enterprise-PKI can be super easy. Take a look at this  https://github.com/Alceatraz/LitePKI

## Setup

You need Nexus OSS:

- Create repository with type "docker-proxy" and name as what ever you like and upstream you need
- Create repository with type "docker-hosted" and named as "docker-hosted"
- Create repository with type "docker-group" and named as "docker-group"
- Add all repository into "docker-group"

You can change repository name:

| header              | mean                             |
|---------------------|----------------------------------|
| OCI-Server          | Manifest Proxy server url prefix |
| OCI-Registry-Group  | Name of type "docker-group"      |
| OCI-Registry-Hosted | Name of type "docker-hosted"     |

## Config

You need nginx for:

- http to https transform
- traffic routing to nexus or proxy

> Non-relative config section omitted.

```nginx
http {

    gzip off;

    client_max_body_size 8192M;

    server {

        set $repository_name docker-group;

        location ~ '^/v2/$' {
            rewrite ^/(.*)$ /repository/$repository_name/$1;
        }

        location ~ '^/v2/(.*?)/manifests/.*?$' {
            if ($request_method ~* PUT) {
                set $repository_name docker-hosted;
            }
            rewrite ^/(.*)$ /repository/$repository_name/$1;
        }

        location ~ '^/v2/(.*?)/blobs/sha256.*?$' {
            rewrite ^/(.*)$ /repository/$repository_name/$1;
        }

        location ~ '^/v2/(.*?)/blobs/uploads/$' {
            set $repository_name docker-hosted;
            rewrite ^/(.*)$ /repository/$repository_name/$1;
        }

        location ~ '^/v2/(.*?)/blobs/uploads/.*?$' {
            set $repository_name docker-hosted;
            rewrite ^/(.*)$ /repository/$repository_name/$1;
        }

        location ~ '^/v2/(.*?)/blobs/uploads/.*?digest=sha256.*$' {
            set $repository_name docker-hosted;
            rewrite ^/(.*)$ /repository/$repository_name/$1;
        }

        location /repository {
            proxy_pass http://127.0.0.1:8081;
            proxy_set_header Host $http_host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_max_temp_file_size 0;
        }
        
        location ~ ^/repository/docker-hosted/v2/.*/manifests {
            proxy_pass http://127.0.0.1:8080;
            proxy_set_header Host $http_host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_max_temp_file_size 0;
            
            # proxy_set_header OCI-Server 'http://127.0.0.1:8081';
            # proxy_set_header OCI-Registry-Group 'docker-group';
            # proxy_set_header OCI-Registry-Hosted 'docker-hosted';
        }
    }
}
```

## Service

```shell
cat > /usr/lib/systemd/system/manifest-proxy.service << EOF
[Unit]
Description=ManifestProxy - Best Nexus OSS companion
Requires=network.target
After=network.target
[Service]
Type=simple
ExecStart=/opt/manifest-proxy/sbin/manifest-proxy
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now manifest-proxy
```