      *================================================================*
      * WXSUMM - Nightly station/day weather summary (legacy batch)    *
      *                                                                *
      * Reads hourly EBCDIC observation records (copybook WXOBS),      *
      * validates them, and writes one summary line per station/day    *
      * using a classic control break. Rejected records go to an       *
      * error file with a reason code.                                 *
      *                                                                *
      * DD-style file assignment (GnuCOBOL filename mapping):          *
      *   OBSIN  -> env DD_OBSIN   input, fixed 40-byte EBCDIC         *
      *   SUMOUT -> env DD_SUMOUT  output, ASCII fixed-width lines     *
      *   ERROUT -> env DD_ERROUT  rejects, ASCII fixed-width lines    *
      *                                                                *
      * Reason codes:                                                  *
      *   R01 hour > 23         R02 wind direction > 360               *
      *   R03 QC flag = X       R04 invalid calendar date              *
      *   R05 non-numeric zoned field after EBCDIC translation         *
      *================================================================*
       IDENTIFICATION DIVISION.
       PROGRAM-ID. WXSUMM.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT OBS-FILE ASSIGN TO "OBSIN"
               ORGANIZATION IS SEQUENTIAL
               FILE STATUS IS WS-OBS-STATUS.
           SELECT SUM-FILE ASSIGN TO "SUMOUT"
               ORGANIZATION IS LINE SEQUENTIAL
               FILE STATUS IS WS-SUM-STATUS.
           SELECT ERR-FILE ASSIGN TO "ERROUT"
               ORGANIZATION IS LINE SEQUENTIAL
               FILE STATUS IS WS-ERR-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD  OBS-FILE
           RECORD CONTAINS 40 CHARACTERS.
           COPY WXOBS.

       FD  SUM-FILE.
       01  SUM-REC                  PIC X(80).

       FD  ERR-FILE.
       01  ERR-REC                  PIC X(80).

       WORKING-STORAGE SECTION.
       01  WS-FILE-STATUS.
           05  WS-OBS-STATUS        PIC XX.
           05  WS-SUM-STATUS        PIC XX.
           05  WS-ERR-STATUS        PIC XX.

       01  WS-FLAGS.
           05  WS-EOF               PIC X      VALUE "N".
               88  END-OF-OBS                  VALUE "Y".
           05  WS-HAVE-GROUP        PIC X      VALUE "N".
               88  GROUP-OPEN                  VALUE "Y".

      *    EBCDIC (CP037) to ASCII for the characters this file uses:
      *    digits, upper-case letters, space, hyphen.
       01  WS-EBCDIC-CHARS.
           05  FILLER  PIC X(10) VALUE X"F0F1F2F3F4F5F6F7F8F9".
           05  FILLER  PIC X(9)  VALUE X"C1C2C3C4C5C6C7C8C9".
           05  FILLER  PIC X(9)  VALUE X"D1D2D3D4D5D6D7D8D9".
           05  FILLER  PIC X(8)  VALUE X"E2E3E4E5E6E7E8E9".
           05  FILLER  PIC X(2)  VALUE X"4060".
       01  WS-ASCII-CHARS.
           05  FILLER  PIC X(10) VALUE "0123456789".
           05  FILLER  PIC X(9)  VALUE "ABCDEFGHI".
           05  FILLER  PIC X(9)  VALUE "JKLMNOPQR".
           05  FILLER  PIC X(8)  VALUE "STUVWXYZ".
           05  FILLER  PIC X(2)  VALUE " -".

       01  WS-PREV-KEY.
           05  WS-PREV-STATION      PIC X(6).
           05  WS-PREV-DATE         PIC 9(8).

       01  WS-ACCUM.
           05  WS-NOBS              PIC 9(5)       COMP.
           05  WS-NTEMP             PIC 9(5)       COMP.
           05  WS-TSUM              PIC S9(9)      COMP-3.
           05  WS-TMIN              PIC S9(4)      COMP-3.
           05  WS-TMAX              PIC S9(4)      COMP-3.
           05  WS-NPRES             PIC 9(5)       COMP.
           05  WS-PSUM              PIC S9(11)     COMP-3.
           05  WS-NPRCP             PIC 9(5)       COMP.
           05  WS-PRCP-SUM          PIC S9(9)      COMP-3.
           05  WS-WMAX              PIC 9(4)       COMP.

       01  WS-RESULTS.
           05  WS-TMIN-D            PIC S9(3)V9    COMP-3.
           05  WS-TMAX-D            PIC S9(3)V9    COMP-3.
           05  WS-TMEAN-D           PIC S9(3)V9    COMP-3.
           05  WS-PMEAN-D           PIC S9(4)V9    COMP-3.
           05  WS-PRCP-D            PIC S9(5)V9    COMP-3.
           05  WS-WMAX-D            PIC 9(3)V9     COMP-3.

       01  WS-COUNTS.
           05  WS-READ-CNT          PIC 9(7)       COMP  VALUE 0.
           05  WS-GOOD-CNT          PIC 9(7)       COMP  VALUE 0.
           05  WS-REJ-CNT           PIC 9(7)       COMP  VALUE 0.
           05  WS-GROUP-CNT         PIC 9(7)       COMP  VALUE 0.

       01  WS-REASON                PIC X(3).
       01  WS-REASON-TEXT           PIC X(30).

       01  WS-HEADER-LINE.
           05  FILLER  PIC X(40) VALUE
               "STATN  DATE     NOB NTP   TMIN   TMAX  T".
           05  FILLER  PIC X(40) VALUE
               "MEAN   PRCP  WMAX  PMEAN                ".

       01  WS-SUM-LINE.
           05  SL-STATION           PIC X(6).
           05  FILLER               PIC X      VALUE SPACE.
           05  SL-DATE              PIC 9(8).
           05  FILLER               PIC X      VALUE SPACE.
           05  SL-NOBS              PIC ZZ9.
           05  FILLER               PIC X      VALUE SPACE.
           05  SL-NTEMP             PIC ZZ9.
           05  FILLER               PIC X      VALUE SPACE.
           05  SL-TEMPS.
               10  SL-TMIN          PIC ---9.9.
               10  FILLER           PIC X      VALUE SPACE.
               10  SL-TMAX          PIC ---9.9.
               10  FILLER           PIC X      VALUE SPACE.
               10  SL-TMEAN         PIC ---9.9.
           05  SL-TEMPS-X REDEFINES SL-TEMPS
                                    PIC X(20).
           05  FILLER               PIC X      VALUE SPACE.
           05  SL-PRCP              PIC ZZZ9.9.
           05  SL-PRCP-X REDEFINES SL-PRCP
                                    PIC X(6).
           05  FILLER               PIC X      VALUE SPACE.
           05  SL-WMAX              PIC ZZ9.9.
           05  FILLER               PIC X      VALUE SPACE.
           05  SL-PMEAN             PIC ZZZ9.9.
           05  FILLER               PIC X(17)  VALUE SPACES.

       01  WS-ERR-LINE.
           05  EL-STATION           PIC X(6).
           05  FILLER               PIC X      VALUE SPACE.
           05  EL-DATE              PIC X(8).
           05  FILLER               PIC X      VALUE SPACE.
           05  EL-HOUR              PIC X(2).
           05  FILLER               PIC X      VALUE SPACE.
           05  EL-REASON            PIC X(3).
           05  FILLER               PIC X      VALUE SPACE.
           05  EL-TEXT              PIC X(30).
           05  FILLER               PIC X(27)  VALUE SPACES.

       01  WS-TRAILER-LINE.
           05  FILLER               PIC X(6)   VALUE "*TOTAL".
           05  FILLER               PIC X(6)   VALUE " READ=".
           05  TL-READ              PIC 9(7).
           05  FILLER               PIC X(10)  VALUE " ACCEPTED=".
           05  TL-GOOD              PIC 9(7).
           05  FILLER               PIC X(10)  VALUE " REJECTED=".
           05  TL-REJ               PIC 9(7).
           05  FILLER               PIC X(8)   VALUE " GROUPS=".
           05  TL-GROUPS            PIC 9(7).
           05  FILLER               PIC X(12)  VALUE SPACES.

       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-OPEN-FILES
           PERFORM 2000-READ-OBS
           PERFORM UNTIL END-OF-OBS
               PERFORM 3000-PROCESS-OBS
               PERFORM 2000-READ-OBS
           END-PERFORM
           IF GROUP-OPEN
               PERFORM 5000-WRITE-SUMMARY
           END-IF
           PERFORM 9000-CLOSE-FILES
           IF WS-REJ-CNT > 0
               MOVE 4 TO RETURN-CODE
           ELSE
               MOVE 0 TO RETURN-CODE
           END-IF
           STOP RUN.

       1000-OPEN-FILES.
           OPEN INPUT OBS-FILE
           IF WS-OBS-STATUS NOT = "00"
               DISPLAY "WXSUMM: OPEN OBSIN FAILED, STATUS "
                       WS-OBS-STATUS
               MOVE 16 TO RETURN-CODE
               STOP RUN
           END-IF
           OPEN OUTPUT SUM-FILE
           OPEN OUTPUT ERR-FILE
           IF WS-SUM-STATUS NOT = "00" OR WS-ERR-STATUS NOT = "00"
               DISPLAY "WXSUMM: OPEN OUTPUT FAILED, STATUS "
                       WS-SUM-STATUS " " WS-ERR-STATUS
               MOVE 16 TO RETURN-CODE
               STOP RUN
           END-IF
           WRITE SUM-REC FROM WS-HEADER-LINE.

       2000-READ-OBS.
           READ OBS-FILE
               AT END
                   SET END-OF-OBS TO TRUE
               NOT AT END
                   ADD 1 TO WS-READ-CNT
           END-READ
           IF NOT END-OF-OBS AND WS-OBS-STATUS NOT = "00"
               DISPLAY "WXSUMM: READ ERROR AT RECORD " WS-READ-CNT
                       " STATUS " WS-OBS-STATUS
               MOVE 16 TO RETURN-CODE
               STOP RUN
           END-IF.

       3000-PROCESS-OBS.
      *    Translate only the DISPLAY fields. Never translate the
      *    whole record: that would corrupt COMP and COMP-3 bytes.
           INSPECT OBS-STATION CONVERTING WS-EBCDIC-CHARS
                                      TO WS-ASCII-CHARS
           INSPECT OBS-DATE    CONVERTING WS-EBCDIC-CHARS
                                      TO WS-ASCII-CHARS
           INSPECT OBS-HOUR    CONVERTING WS-EBCDIC-CHARS
                                      TO WS-ASCII-CHARS
           INSPECT OBS-QC      CONVERTING WS-EBCDIC-CHARS
                                      TO WS-ASCII-CHARS
           PERFORM 3100-VALIDATE
           IF WS-REASON = SPACES
               ADD 1 TO WS-GOOD-CNT
               PERFORM 4000-ACCUMULATE
           ELSE
               ADD 1 TO WS-REJ-CNT
               PERFORM 6000-WRITE-ERROR
           END-IF.

       3100-VALIDATE.
           MOVE SPACES TO WS-REASON
           EVALUATE TRUE
               WHEN OBS-DATE IS NOT NUMERIC
                 OR OBS-HOUR IS NOT NUMERIC
                   MOVE "R05" TO WS-REASON
                   MOVE "NON-NUMERIC ZONED FIELD" TO WS-REASON-TEXT
               WHEN FUNCTION TEST-DATE-YYYYMMDD(OBS-DATE) NOT = 0
                   MOVE "R04" TO WS-REASON
                   MOVE "INVALID CALENDAR DATE" TO WS-REASON-TEXT
               WHEN OBS-HOUR > 23
                   MOVE "R01" TO WS-REASON
                   MOVE "HOUR OUT OF RANGE" TO WS-REASON-TEXT
               WHEN OBS-WDIR > 360
                   MOVE "R02" TO WS-REASON
                   MOVE "WIND DIRECTION OUT OF RANGE" TO WS-REASON-TEXT
               WHEN OBS-QC = "X"
                   MOVE "R03" TO WS-REASON
                   MOVE "REJECTED BY QC FLAG" TO WS-REASON-TEXT
           END-EVALUATE.

       4000-ACCUMULATE.
           IF NOT GROUP-OPEN
              OR OBS-STATION NOT = WS-PREV-STATION
              OR OBS-DATE    NOT = WS-PREV-DATE
               IF GROUP-OPEN
                   PERFORM 5000-WRITE-SUMMARY
               END-IF
               PERFORM 4100-START-GROUP
           END-IF
           ADD 1 TO WS-NOBS
           IF OBS-TEMP NOT = -9999
               ADD 1 TO WS-NTEMP
               ADD OBS-TEMP TO WS-TSUM
               IF WS-NTEMP = 1 OR OBS-TEMP < WS-TMIN
                   MOVE OBS-TEMP TO WS-TMIN
               END-IF
               IF WS-NTEMP = 1 OR OBS-TEMP > WS-TMAX
                   MOVE OBS-TEMP TO WS-TMAX
               END-IF
           END-IF
           ADD 1 TO WS-NPRES
           ADD OBS-PRESS TO WS-PSUM
           IF OBS-PRCP NOT = -9999
               ADD 1 TO WS-NPRCP
               ADD OBS-PRCP TO WS-PRCP-SUM
           END-IF
           IF OBS-WSPD > WS-WMAX
               MOVE OBS-WSPD TO WS-WMAX
           END-IF.

       4100-START-GROUP.
           MOVE OBS-STATION TO WS-PREV-STATION
           MOVE OBS-DATE    TO WS-PREV-DATE
           INITIALIZE WS-ACCUM
           SET GROUP-OPEN TO TRUE.

       5000-WRITE-SUMMARY.
           MOVE WS-PREV-STATION TO SL-STATION
           MOVE WS-PREV-DATE    TO SL-DATE
           MOVE WS-NOBS         TO SL-NOBS
           MOVE WS-NTEMP        TO SL-NTEMP
           IF WS-NTEMP = 0
               MOVE "   N/A    N/A    N/A" TO SL-TEMPS-X
           ELSE
               COMPUTE WS-TMIN-D = WS-TMIN / 10
               COMPUTE WS-TMAX-D = WS-TMAX / 10
               COMPUTE WS-TMEAN-D ROUNDED =
                       WS-TSUM / (WS-NTEMP * 10)
               MOVE WS-TMIN-D  TO SL-TMIN
               MOVE WS-TMAX-D  TO SL-TMAX
               MOVE WS-TMEAN-D TO SL-TMEAN
           END-IF
           IF WS-NPRCP = 0
               MOVE "   N/A" TO SL-PRCP-X
           ELSE
               COMPUTE WS-PRCP-D = WS-PRCP-SUM / 10
               MOVE WS-PRCP-D TO SL-PRCP
           END-IF
           COMPUTE WS-WMAX-D = WS-WMAX / 10
           MOVE WS-WMAX-D TO SL-WMAX
           COMPUTE WS-PMEAN-D ROUNDED = WS-PSUM / (WS-NPRES * 10)
           MOVE WS-PMEAN-D TO SL-PMEAN
           WRITE SUM-REC FROM WS-SUM-LINE
           ADD 1 TO WS-GROUP-CNT.

       6000-WRITE-ERROR.
           MOVE OBS-STATION    TO EL-STATION
           MOVE OBS-DATE       TO EL-DATE
           MOVE OBS-HOUR       TO EL-HOUR
           MOVE WS-REASON      TO EL-REASON
           MOVE WS-REASON-TEXT TO EL-TEXT
           WRITE ERR-REC FROM WS-ERR-LINE.

       9000-CLOSE-FILES.
           MOVE WS-READ-CNT  TO TL-READ
           MOVE WS-GOOD-CNT  TO TL-GOOD
           MOVE WS-REJ-CNT   TO TL-REJ
           MOVE WS-GROUP-CNT TO TL-GROUPS
           WRITE SUM-REC FROM WS-TRAILER-LINE
           DISPLAY "WXSUMM: READ=" WS-READ-CNT
                   " ACCEPTED=" WS-GOOD-CNT
                   " REJECTED=" WS-REJ-CNT
                   " GROUPS=" WS-GROUP-CNT
           CLOSE OBS-FILE SUM-FILE ERR-FILE.
