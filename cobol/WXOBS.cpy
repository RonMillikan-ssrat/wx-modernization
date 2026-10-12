      *================================================================*
      * WXOBS  - Hourly surface observation record (LRECL = 40)        *
      *                                                                *
      * Offset Len  Field          Picture             Encoding        *
      *   0     6   OBS-STATION    X(6)                EBCDIC text     *
      *   6     8   OBS-DATE       9(8)  YYYYMMDD      EBCDIC zoned    *
      *  14     2   OBS-HOUR       9(2)                EBCDIC zoned    *
      *  16     3   OBS-TEMP       S9(4) COMP-3        tenths deg C    *
      *  19     3   OBS-DEWPT      S9(4) COMP-3        tenths deg C    *
      *  22     3   OBS-PRESS      S9(5) COMP-3        tenths hPa      *
      *  25     2   OBS-WDIR       9(4)  COMP          degrees, BE     *
      *  27     2   OBS-WSPD       9(4)  COMP          tenths knot, BE *
      *  29     3   OBS-PRCP       S9(5) COMP-3        tenths mm       *
      *  32     1   OBS-QC         X                   EBCDIC text     *
      *  33     7   FILLER         X(7)                                *
      *                                                                *
      * Missing value sentinel: -9999 in TEMP, DEWPT, PRCP.            *
      * COMP fields are big-endian (GnuCOBOL default byte order).      *
      *================================================================*
       01  OBS-RECORD.
           05  OBS-STATION          PIC X(6).
           05  OBS-DATE             PIC 9(8).
           05  OBS-HOUR             PIC 9(2).
           05  OBS-TEMP             PIC S9(4)  COMP-3.
           05  OBS-DEWPT            PIC S9(4)  COMP-3.
           05  OBS-PRESS            PIC S9(5)  COMP-3.
           05  OBS-WDIR             PIC 9(4)   COMP.
           05  OBS-WSPD             PIC 9(4)   COMP.
           05  OBS-PRCP             PIC S9(5)  COMP-3.
           05  OBS-QC               PIC X.
           05  FILLER               PIC X(7).
