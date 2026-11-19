#!/bin/sh
# Same contract as the upstream standalone/server.sh: JAVA_OPTS, MAXTHREADS, PORT, BINDADDRESS
exec java $JAVA_OPTS -cp /app/brouter.jar btools.server.RouteServer \
  "${SEGMENTSPATH:-/segments4}" "${PROFILESPATH:-/profiles2}" "${CUSTOMPROFILESPATH:-/customprofiles}" \
  "${PORT:-17777}" "${MAXTHREADS:-1}" ${BINDADDRESS:+$BINDADDRESS}
