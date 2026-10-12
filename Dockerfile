# Lift-and-shift image: the unchanged COBOL program plus the job wrapper.
#
# Multi-stage: compile with the full GnuCOBOL toolchain, then ship only the
# runtime library, the executable, Python (for the parity step) and scripts.
FROM ubuntu:24.04 AS build
RUN apt-get update \
 && apt-get install -y --no-install-recommends gnucobol \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY cobol/ cobol/
RUN cobc -x -std=default -I cobol -o /src/wxsumm cobol/WXSUMM.cbl

FROM ubuntu:24.04
RUN apt-get update \
 && apt-get install -y --no-install-recommends libcob4t64 python3 \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/wx
COPY --from=build /src/wxsumm bin/wxsumm
COPY scripts/run_job.sh scripts/parity_check.py scripts/
RUN chmod +x bin/wxsumm scripts/run_job.sh
ENTRYPOINT ["/opt/wx/scripts/run_job.sh"]
