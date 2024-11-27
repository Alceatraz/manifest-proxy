FROM hub.rczy.cc/library/alpine:latest
COPY target/manifest-proxy /opt/manifest-proxy/bin/manifest-proxy
RUN chmod +x /opt/manifest-proxy/bin/manifest-proxy
CMD /opt/manifest-proxy/bin/manifest-proxy