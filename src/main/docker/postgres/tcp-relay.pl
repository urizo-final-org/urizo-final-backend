use strict;
use warnings;
use IO::Select;
use IO::Socket::INET;

$SIG{CHLD} = 'IGNORE';

my $listener = IO::Socket::INET->new(
    LocalAddr => '0.0.0.0',
    LocalPort => 15432,
    Listen => 32,
    Reuse => 1,
    Proto => 'tcp'
) or die "failed to bind local database gateway\n";

while (my $client = $listener->accept()) {
    my $pid = fork();
    unless (defined $pid) {
        close $client;
        next;
    }

    if ($pid == 0) {
        close $listener;
        relay_connection($client);
        exit 0;
    }

    close $client;
}

sub relay_connection {
    my ($client) = @_;
    my $database = IO::Socket::INET->new(
        PeerHost => 'database',
        PeerPort => 5432,
        Proto => 'tcp',
        Timeout => 5
    );
    unless ($database) {
        close $client;
        return;
    }

    my $select = IO::Select->new($client, $database);
    CONNECTION: while (1) {
        my @ready = $select->can_read(60);
        last unless @ready;

        for my $input (@ready) {
            my $buffer = '';
            my $length = sysread($input, $buffer, 16384);
            last CONNECTION unless defined($length) && $length > 0;

            my $output = fileno($input) == fileno($client) ? $database : $client;
            my $offset = 0;
            while ($offset < $length) {
                my $written = syswrite($output, $buffer, $length - $offset, $offset);
                last CONNECTION unless defined($written) && $written > 0;
                $offset += $written;
            }
        }
    }

    close $database;
    close $client;
}
