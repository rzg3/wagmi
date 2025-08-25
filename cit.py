from collections import Counter
arr = [0,3,4]

p = 0
arr.sort()
counts = Counter(arr)

l = 0
res = []
for i in range(len(arr)):
    n = arr[i]
    if i == 0 and n != 0:
        res.append(0)
        print(i, n)
        break
    if n > l + 1:
        break
    if counts[n] == 1:
        res.append(n)
    l = n
print(res)
