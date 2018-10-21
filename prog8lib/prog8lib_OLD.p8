; Prog8 internal library routines - always included by the compiler
;
; Written by Irmen de Jong (irmen@razorvine.net) - license: GNU GPL 3.0
;
; indent format: TABS, size=8


~ prog8_lib {
		; note: the following ZP scratch registers must be the same as in c64lib
		memory  byte  SCRATCH_ZP1	= $02		; scratch register #1 in ZP
		memory  byte  SCRATCH_ZP2	= $03		; scratch register #2 in ZP
		memory  word  SCRATCH_ZPWORD1	= $fb		; scratch word in ZP ($fb/$fc)
		memory  word  SCRATCH_ZPWORD2	= $fd		; scratch word in ZP ($fd/$fe)


	%asm {{
; ---- jmp (indirect) routines for register pairs containing the indirect address
; @todo still needed??
jsr_indirect_nozpuse_AX
		sta  jsr_indirect_tmp
		stx  jsr_indirect_tmp+1
		jmp  (jsr_indirect_tmp)
jsr_indirect_nozpuse_AY
		sta  jsr_indirect_tmp
		sty  jsr_indirect_tmp+1
		jmp  (jsr_indirect_tmp)
jsr_indirect_nozpuse_XY
		stx  jsr_indirect_tmp
		sty  jsr_indirect_tmp+1
		jmp  (jsr_indirect_tmp)
jsr_indirect_tmp
		.byte 0, 0


jsr_indirect_AX
		sta  SCRATCH_ZP1
		stx  SCRATCH_ZP2
		jmp  (SCRATCH_ZP1)
jsr_indirect_AY
		sta  SCRATCH_ZP1
		sty  SCRATCH_ZP2
		jmp  (SCRATCH_ZP1)
jsr_indirect_XY
		stx  SCRATCH_ZP1
		sty  SCRATCH_ZP2
		jmp  (SCRATCH_ZP1)


; copy memory UP from (SCRATCH_ZPWORD1) to (SCRATCH_ZPWORD2) of length X/Y (16-bit, X=lo, Y=hi)
; clobbers register A,X,Y
memcopy16_up
		source = SCRATCH_ZPWORD1
		dest = SCRATCH_ZPWORD2
		length = SCRATCH_ZP1   ; (and SCRATCH_ZP2)

		stx  length
		sty  length+1

		ldx  length             ; move low byte of length into X
		bne  +                  ; jump to start if X > 0
		dec  length             ; subtract 1 from length
+		ldy  #0                 ; set Y to 0
-		lda  (source),y         ; set A to whatever (source) points to offset by Y
		sta  (dest),y           ; move A to location pointed to by (dest) offset by Y
		iny                     ; increment Y
		bne  +                  ; if Y<>0 then (rolled over) then still moving bytes
		inc  source+1           ; increment hi byte of source
		inc  dest+1             ; increment hi byte of dest
+		dex                     ; decrement X (lo byte counter)
		bne  -                  ; if X<>0 then move another byte
		dec  length             ; weve moved 255 bytes, dec length
		bpl  -                  ; if length is still positive go back and move more
		rts                     ; done


; copy memory UP from (SCRATCH_ZPWORD1) to (AY) with length X (1 to 256, 0 meaning 256)
; destination must not overlap, or be before start, then overlap is possible.
; clobbers A, X, Y

memcopy
		sta  SCRATCH_ZPWORD2
		sty  SCRATCH_ZPWORD2+1
		ldy  #0
-		lda  (SCRATCH_ZPWORD1), y
		sta  (SCRATCH_ZPWORD2), y
		iny
		dex
		bne  -
		rts


; fill memory from (SCRATCH_ZPWORD1), length XY, with value in A.
; clobbers X, Y
memset          stx  SCRATCH_ZP1
		sty  SCRATCH_ZP2
		ldy  #0
		ldx  SCRATCH_ZP2
		beq  _lastpage

_fullpage	sta  (SCRATCH_ZPWORD1),y
		iny
		bne  _fullpage
		inc  SCRATCH_ZPWORD1+1          ; next page
		dex
		bne  _fullpage

_lastpage	ldy  SCRATCH_ZP1
		beq  +
-         	dey
		sta  (SCRATCH_ZPWORD1),y
		bne  -

+           	rts



; fill memory from (SCRATCH_ZPWORD1) number of words in SCRATCH_ZPWORD2, with word value in AY.
; clobbers A, X, Y
memsetw
		sta  _mod1+1                    ; self-modify
		sty  _mod1b+1                   ; self-modify
		sta  _mod2+1                    ; self-modify
		sty  _mod2b+1                   ; self-modify
		ldx  SCRATCH_ZPWORD1
		stx  SCRATCH_ZP1
		ldx  SCRATCH_ZPWORD1+1
		inx
		stx  SCRATCH_ZP2                ; second page

		ldy  #0
		ldx  SCRATCH_ZPWORD2+1
		beq  _lastpage

_fullpage
_mod1           lda  #0                         ; self-modified
		sta  (SCRATCH_ZPWORD1),y        ; first page
		sta  (SCRATCH_ZP1),y            ; second page
		iny
_mod1b		lda  #0                         ; self-modified
		sta  (SCRATCH_ZPWORD1),y        ; first page
		sta  (SCRATCH_ZP1),y            ; second page
		iny
		bne  _fullpage
		inc  SCRATCH_ZPWORD1+1          ; next page pair
		inc  SCRATCH_ZPWORD1+1          ; next page pair
		inc  SCRATCH_ZP1+1              ; next page pair
		inc  SCRATCH_ZP1+1              ; next page pair
		dex
		bne  _fullpage

_lastpage	ldx  SCRATCH_ZPWORD2
		beq  _done

		ldy  #0
-
_mod2           lda  #0                         ; self-modified
                sta  (SCRATCH_ZPWORD1), y
		inc  SCRATCH_ZPWORD1
		bne  _mod2b
		inc  SCRATCH_ZPWORD1+1
_mod2b          lda  #0                         ; self-modified
		sta  (SCRATCH_ZPWORD1), y
		inc  SCRATCH_ZPWORD1
		bne  +
		inc  SCRATCH_ZPWORD1+1
+               dex
		bne  -
_done		rts


; increments/decrements a byte referenced by indirect register pair by 1
; clobbers A/Y,  Carry flag determines incr or decr
incrdecr_deref_byte_reg_AX
		sta  SCRATCH_ZPWORD1
		stx  SCRATCH_ZPWORD1+1
		bcc  incr_deref_byte
		bcs  decr_deref_byte
incrdecr_deref_byte_reg_AY
		sta  SCRATCH_ZPWORD1
		sty  SCRATCH_ZPWORD1+1
		bcc  incr_deref_byte
		bcs  decr_deref_byte
incrdecr_deref_byte_reg_XY
		stx  SCRATCH_ZPWORD1
		sty  SCRATCH_ZPWORD1+1
		bcs  decr_deref_byte

incr_deref_byte
		ldy  #0
		lda  (SCRATCH_ZPWORD1), y
		adc  #1         ; carry's cleared already
		sta  (SCRATCH_ZPWORD1), y
		rts
decr_deref_byte
		ldy  #0
		lda  (SCRATCH_ZPWORD1), y
		sbc  #1         ; carry's set already
		sta  (SCRATCH_ZPWORD1), y
		rts

; increments/decrements a word referenced by indirect register pair by 1
; clobbers A/Y,  Carry flag determines incr or decr
incrdecr_deref_word_reg_AX
		sta  SCRATCH_ZPWORD1
		stx  SCRATCH_ZPWORD1+1
		bcc  incr_deref_word
		bcs  decr_deref_word
incrdecr_deref_word_reg_AY
		sta  SCRATCH_ZPWORD1
		sty  SCRATCH_ZPWORD1+1
		bcc  incr_deref_word
		bcs  decr_deref_word
incrdecr_deref_word_reg_XY
		stx  SCRATCH_ZPWORD1
		sty  SCRATCH_ZPWORD1+1
		bcs  decr_deref_word

incr_deref_word
		ldy  #0
		lda  (SCRATCH_ZPWORD1), y
		adc  #1         ; carry's cleared already
		sta  (SCRATCH_ZPWORD1), y
		bcc  +
		iny
		lda  (SCRATCH_ZPWORD1), y
		adc  #0         ; carry is set
		sta  (SCRATCH_ZPWORD1), y
+       	rts

decr_deref_word
		ldy  #0
		lda  (SCRATCH_ZPWORD1), y
		bne  +
		pha
		iny
		lda  (SCRATCH_ZPWORD1), y
		sbc  #1         ; carry's set already
		sta  (SCRATCH_ZPWORD1), y
		dey
		pla
+       	sec
		sbc  #1
		sta  (SCRATCH_ZPWORD1), y
		rts


; shift bits in A right by X positions
lsr_A_by_X
		cpx  #8
		bcc  _shift
		lda  #0         ; x >=8, result always 0
		rts
_shift		cpx  #0
		beq  +
-               lsr  a
		dex
		bne  -
+		rts


; shift bits in A left by X positions
asl_A_by_X
		cpx  #8
		bcc  _shift
		lda  #0         ; x >=8, result always 0
		rts
_shift		cpx  #0
		beq  +
-               asl  a
		dex
		bne  -
+		rts


	}}
}
