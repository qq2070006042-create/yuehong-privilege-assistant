obj-m += dfroot.o

# Size diet: this ko is written through the exploit page by page,
# so every byte counts. -Os shrinks code; dropping unwind tables kills
# .eh_frame (unused for modules).
CFLAGS_dfroot.o := -Os -fno-asynchronous-unwind-tables -fno-unwind-tables

all:
	$(MAKE) -C $(KDIR) M=$(CURDIR) \
		ARCH=arm64 \
		LLVM=1 \
		modules

clean:
	$(MAKE) -C $(KDIR) M=$(CURDIR) \
		ARCH=arm64 \
		LLVM=1 \
		clean
	rm -f dfroot.lds

.PHONY: all clean
