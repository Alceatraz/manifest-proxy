# Manifest Proxy

## Setup

### Create SSL certificate

Create PKI can be very easy https://github.com/Alceatraz/LitePKI

### Setup Nexus

- Create repository with type "docker-proxy" for all upstream you need and what ever name
- Create repository with type "docker-hosted" and named as "docker-hosted"
- Create repository with type "docker-group" and named as "docker-group"
- Append repository into "docker-group" repository

### Setup Nginx

```text
set $REPOSITORY_GROUP 'docker-group';
set $REPOSITORY_HOSTED 'docker-hosted';

set $repository_name $REPOSITORY_GROUP;

# Push step 1
location ~ '^/v2/(.*?)/blobs/uploads/$' {
	set $repository_name $REPOSITORY_HOSTED;
	rewrite ^/(.*)$ /repository/$repository_name/$1;
}

# Push step 2
location ~ '^/v2/(.*?)/blobs/uploads/.*?$' {
	set $repository_name $REPOSITORY_HOSTED;
	rewrite ^/(.*)$ /repository/$repository_name/$1;
}

# Push step 3
location ~ '^/v2/(.*?)/blobs/uploads/.*?digest=sha256.*$' {
	set $repository_name $REPOSITORY_HOSTED;
	rewrite ^/(.*)$ /repository/$repository_name/$1;
}

# Pull | Push step 4
location ~ '^/v2/(.*?)/manifests/.*?$' {
	if ($request_method ~* PUT) {
		set $repository_name $REPOSITORY_HOSTED;
	}
	rewrite ^/(.*)$ /repository/$repository_name/$1;
}

# Pull
location ~ '^/v2/(.*?)/blobs/sha256.*?$' {
	rewrite ^/(.*)$ /repository/$repository_name/$1;
}

# Token
location ~ '^/v2/token$' {
	rewrite ^/(.*)$ /repository/$repository_name/$1;
	proxy_pass 'http://localhost:8081';
}

# Login
location ~ '^/v2/$' {
	rewrite ^/(.*)$ /repository/$repository_name/$1;
	proxy_pass 'http://localhost:8081';
}

# to Proxy
location ~ ^/repository/docker-hosted/v2/.*/manifests {
	proxy_pass 'http://localhost:8080';
	proxy_set_header Host            $http_host;
	proxy_set_header X-Real-IP       $remote_addr;
	proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
	# proxy_set_header OCI-Server          'http://localhost:8081';
	# proxy_set_header OCI-Registry-Group  'docker-group';
	# proxy_set_header OCI-Registry-Hosted 'docker-hosted';
	proxy_max_temp_file_size 0;
}

# to Nexus
location / {
	proxy_pass 'http://localhost:8081';
	proxy_set_header Host            $http_host;
	proxy_set_header X-Real-IP       $remote_addr;
	proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
	proxy_max_temp_file_size 0;
}
```

You can change repository name with headers:

| key                 | mean                         | default               |
|---------------------|------------------------------|-----------------------|
| OCI-Server          | NXRM server url              | http://localhost:8081 |
| OCI-Registry-Group  | Name of type "docker-group"  | docker-group          |
| OCI-Registry-Hosted | Name of type "docker-hosted" | docker-hosted         |

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

## What if you don't want 8080 port

Create a `application.properties` because it's a spring boot application

```properties
 server.port=12345
```
