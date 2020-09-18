import time
import json
import re


# noinspection DuplicatedCode
def gc_functions_handler(request):
	n = None
	# available_start, memory_total = memory_stats(True)

	if request.args.get('n') is not None:
		n = int(request.args.get('n'))
	else:
		n = 33

	start_time = time.time()
	result = fibonacci(n)
	end_time = time.time()
	execution_time = (end_time - start_time) * 1000

	# available_end, _ = memory_stats(False)

	headers = {
		'Content-Type': 'application/json'
	}

	return (json.dumps({
		'success': True,
		'payload': {
			'test': 'memory_test',
			'number': n,
			'result': result,
			'milliseconds': execution_time
		}  # ,
		# 'memory_info': {
		# 'initial_available': available_start,
		# 'final_available': available_end,
		# 'total': memory_total
		# }
	}), 200, headers)


# noinspection DuplicatedCode
def memory_stats(total):
	memory_info, memory_total = None, None

	f = open('/proc/meminfo', 'r')
	if f.mode == 'r':
		memory_info = f.read()
	f.close()

	if total:
		total_pattern = re.compile("(MemTotal:\s)(.+B)")
		memory_total = total_pattern.search(memory_info)[2]

	available_pattern = re.compile("(MemAvailable:\s)(.+B)")
	memory_available = available_pattern.search(memory_info)[2]

	return memory_available, memory_total


# noinspection DuplicatedCode
def fibonacci(n):
	if n <= 1:
		return n
	else:
		return fibonacci(n - 1) + fibonacci(n - 2)