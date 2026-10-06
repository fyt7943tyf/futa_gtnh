"""看看共享存储的存档文件里到底存了什么（不用开游戏）。

为什么要这个工具：「仓库里明明有 X，规划却报缺 X」这类问题必须能直接看到
存储里的<b>原始身份</b>（流体的注册名、物品的注册名+meta），而不是靠猜 ——
界面上显示的是本地化名字，存档里存的是注册名，两边对不上的时候只有这里看得见。

用法：
    python DumpSharedStorage.py <futa_gtnh_shared.dat> [关键词]

关键词会去匹配流体的注册名和物品的注册名（大小写不敏感）。
"""

import gzip
import struct
import sys

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


class Reader:

    def __init__(self, data):
        self.data = data
        self.pos = 0

    def take(self, n):
        chunk = self.data[self.pos:self.pos + n]
        self.pos += n
        return chunk

    def u1(self):
        return struct.unpack('>B', self.take(1))[0]

    def i1(self):
        return struct.unpack('>b', self.take(1))[0]

    def i2(self):
        return struct.unpack('>h', self.take(2))[0]

    def i4(self):
        return struct.unpack('>i', self.take(4))[0]

    def i8(self):
        return struct.unpack('>q', self.take(8))[0]

    def f4(self):
        return struct.unpack('>f', self.take(4))[0]

    def f8(self):
        return struct.unpack('>d', self.take(8))[0]

    def string(self):
        length = struct.unpack('>H', self.take(2))[0]
        return self.take(length).decode('utf-8', 'replace')

    def payload(self, tag_type):
        if tag_type == TAG_BYTE:
            return self.i1()
        if tag_type == TAG_SHORT:
            return self.i2()
        if tag_type == TAG_INT:
            return self.i4()
        if tag_type == TAG_LONG:
            return self.i8()
        if tag_type == TAG_FLOAT:
            return self.f4()
        if tag_type == TAG_DOUBLE:
            return self.f8()
        if tag_type == TAG_BYTE_ARRAY:
            return self.take(self.i4())
        if tag_type == TAG_STRING:
            return self.string()
        if tag_type == TAG_LIST:
            item_type = self.u1()
            count = self.i4()
            return [self.payload(item_type) for _ in range(count)]
        if tag_type == TAG_COMPOUND:
            out = {}
            while True:
                child = self.u1()
                if child == TAG_END:
                    return out
                name = self.string()
                out[name] = self.payload(child)
        if tag_type == TAG_INT_ARRAY:
            return [self.i4() for _ in range(self.i4())]
        if tag_type == TAG_LONG_ARRAY:
            return [self.i8() for _ in range(self.i4())]
        raise ValueError('unknown tag %d at %d' % (tag_type, self.pos))


def load(path):
    with open(path, 'rb') as handle:
        head = handle.read(2)
    if head == b'\x1f\x8b':
        with gzip.open(path, 'rb') as handle:
            data = handle.read()
    else:
        with open(path, 'rb') as handle:
            data = handle.read()
    reader = Reader(data)
    tag_type = reader.u1()
    reader.string()
    return reader.payload(tag_type)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    root = load(sys.argv[1])
    keyword = sys.argv[2] if len(sys.argv) > 2 else None
    lower = keyword.lower() if keyword else None

    items = root.get('items') or []
    fluids = root.get('fluids') or []
    print('存档：物品 %d 种，流体 %d 种' % (len(items), len(fluids)))

    if lower:
        print('--- 流体里匹配「%s」的 ---' % keyword)
        for entry in fluids:
            name = entry.get('fluid') or ''
            if lower in name.lower():
                print('  %-40s %14d mB   nbt=%s' % (name, entry.get('amount', 0), 'yes' if 'nbt' in entry else 'no'))
        print('--- 物品里匹配「%s」的（前 20 条）---' % keyword)
        shown = 0
        for entry in items:
            name = entry.get('item') or entry.get('registry') or ''
            if lower in str(name).lower():
                print('  %-60s meta=%-6s %12d' % (name, entry.get('meta', entry.get('damage', '?')), entry.get('amount', 0)))
                shown += 1
                if shown >= 20:
                    break
        return

    print('--- 流体（按量排序，前 30）---')
    for entry in sorted(fluids, key=lambda e: -e.get('amount', 0))[:30]:
        print('  %-40s %14d mB' % (entry.get('fluid') or '', entry.get('amount', 0)))


if __name__ == '__main__':
    main()
