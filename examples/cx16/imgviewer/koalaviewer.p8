%target cx16
%import graphics
%import textio
%import diskio

main {
    const uword load_location = $4000
    ubyte screen_color

    sub start() {

        str[] pictures = [
            "i01-blubb-sphinx.bin",
            "i02-bugjam-jsl.bin",
            "i03-dinothawr-ar.bin",
            "i04-fox-leon.bin",
            "i05-hunter-agod.bin",
            "i06-jazzman-jds.bin",
            "i07-katakis-jegg.bin"
        ]

        graphics.enable_bitmap_mode()
        repeat {
            ubyte file_idx
            for file_idx in 0 to len(pictures)-1 {
                load_image(pictures[file_idx])
                wait()
            }
        }
    }

    sub wait() {
        uword jiffies = 0
        c64.SETTIM(0,0,0)

        while jiffies < 180 {
            ; read clock
            %asm {{
                stx  P8ZP_SCRATCH_REG
                jsr  c64.RDTIM
                sta  jiffies
                stx  jiffies+1
                ldx  P8ZP_SCRATCH_REG
            }}
        }
    }

    sub load_image(uword filenameptr) {
        uword length = diskio.load(8, filenameptr, load_location)

        if length != 10001 {
            txt.print_uw(length)
            txt.print("\nload error\n")
            diskio.status(8)
            exit(1)
        }
        convert_koalapic()
    }

    sub convert_koalapic() {
        ubyte cx
        ubyte cy
        uword cy_times_forty = 0
        ubyte bb
        ubyte c0
        ubyte c1
        ubyte c2
        ubyte c3
        uword bitmap = load_location

        screen_color = @(load_location + 8000 + 1000 + 1000) & 15

        for cy in 0 to 24 {
            for cx in 0 to 39 {
                for bb in 0 to 7 {
                    cx16.r0 = cx * $0008
                    cx16.r1 = cy * 8 + bb
                    cx16.FB_cursor_position()
                    get_4_pixels()
                    cx16.FB_set_pixel(c0)
                    cx16.FB_set_pixel(c0)
                    cx16.FB_set_pixel(c1)
                    cx16.FB_set_pixel(c1)
                    cx16.FB_set_pixel(c2)
                    cx16.FB_set_pixel(c2)
                    cx16.FB_set_pixel(c3)
                    cx16.FB_set_pixel(c3)
                    bitmap++
                }
            }
            cy_times_forty += 40
        }

        sub get_4_pixels() {
            c0 = mcol(@(bitmap)>>6)
            c1 = mcol(@(bitmap)>>4)
            c2 = mcol(@(bitmap)>>2)
            c3 = mcol(@(bitmap))

            sub mcol(ubyte b) -> ubyte {
                ubyte color
                when b & 3 {
                    0 -> color = screen_color
                    1 -> color = @(load_location + 8000 + cy_times_forty + cx) >>4
                    2 -> color = @(load_location + 8000 + cy_times_forty + cx) & 15
                    else -> color = @(load_location + 8000 + 1000 + cy_times_forty + cx) & 15
                }
                return color
            }
        }
    }
}