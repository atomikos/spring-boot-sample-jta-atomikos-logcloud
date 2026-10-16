#!/bin/sh
# Starts a demo node with its transaction log in one of two places:
#   TRANSACTION_LOG=logcloud (default)  in PostgreSQL, shared by all nodes
#   TRANSACTION_LOG=file                on this container's own disk
# Atomikos picks LogCloud whenever the transactions-logcloud jar is on the
# classpath, so file mode simply leaves that one jar out.
set -eu

TRANSACTION_LOG="${TRANSACTION_LOG:-logcloud}"

CLASSPATH=/app/classes
for jar in /app/lib/*.jar; do
	# exactly transactions-logcloud-<version>, not transactions-spring-boot-logcloud
	if [ "$TRANSACTION_LOG" = file ] && case "$(basename "$jar")" in transactions-logcloud-[0-9]*) true ;; *) false ;; esac; then
		continue
	fi
	CLASSPATH="$CLASSPATH:$jar"
done

if [ "$TRANSACTION_LOG" = file ]; then
	# With file logs every node needs its own name: a node must not mistake another
	# node's prepared transactions for its own (and roll them back for lack of a log).
	export ATOMIKOS_PROPERTIES_TRANSACTIONMANAGERUNIQUENAME="$NODE_NAME"
fi

echo "Starting $NODE_NAME with its transaction log in: $TRANSACTION_LOG"
exec java -cp "$CLASSPATH" com.atomikos.demo.Application
